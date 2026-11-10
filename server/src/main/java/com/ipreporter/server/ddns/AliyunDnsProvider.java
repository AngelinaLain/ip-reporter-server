package com.ipreporter.server.ddns;

import com.aliyuncs.AcsRequest;
import com.aliyuncs.AcsResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.*;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.HttpClientConfig;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class AliyunDnsProvider implements DnsProvider {
    private final Function<DnsCredentials, IAcsClient> clients;

    public AliyunDnsProvider() { this(AliyunDnsProvider::client); }
    AliyunDnsProvider(Function<DnsCredentials, IAcsClient> clients) { this.clients = clients; }
    @Override public String type() { return "ALIYUN"; }

    static IAcsClient client(DnsCredentials credentials) {
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou", credentials.accessKeyId(), credentials.accessKeySecret());
        // SDK 默认配置会设置 HTTP 实现类型；空构造器的 clientType 为 null，构造客户端时就会失败。
        HttpClientConfig http = HttpClientConfig.getDefault();
        http.setConnectionTimeoutMillis(3000);
        http.setReadTimeoutMillis(10000);
        http.setWriteTimeoutMillis(10000);
        http.setIgnoreSSLCerts(false);
        http.setProtocolType(ProtocolType.HTTPS);
        profile.setHttpClientConfig(http);
        DefaultAcsClient client = new DefaultAcsClient(profile);
        client.setAutoRetry(false);
        client.setMaxRetryNumber(0);
        return client;
    }

    private static <T extends AcsResponse> T send(IAcsClient client, AcsRequest<T> request, long deadline) throws ClientException {
        DnsProviderSupport.checkDeadline(deadline);
        request.setSysProtocol(ProtocolType.HTTPS);
        request.setSysEndpoint("alidns.aliyuncs.com");
        return client.getAcsResponse(request);
    }

    @Override public DnsSyncResult upsert(DnsCredentials credentials, DnsRecord desired) {
        DnsProviderSupport.validate(credentials, desired);
        IAcsClient client = null;
        String operation = "初始化客户端";
        final long deadline = DnsProviderSupport.operationDeadline();
        try {
            client = clients.apply(credentials);
            operation = "查询解析记录";
            String domain = DnsProviderSupport.domain(desired);
            String rr = DnsProviderSupport.rr(desired);
            String line = DnsProviderSupport.line(desired, "default");
            List<DescribeDomainRecordsResponse.Record> matches = new ArrayList<>();
            // 即使服务端提供搜索，也对每页结果再次精确核对，禁止模糊匹配误改。
            for (int page = 1; ; page++) {
                if (page > DnsProviderSupport.MAX_PAGES) throw new DnsProviderException("阿里云记录数量超过单次安全查询上限，请缩小范围");
                DescribeDomainRecordsRequest request = new DescribeDomainRecordsRequest();
                request.setDomainName(domain);
                request.setRRKeyWord(rr);
                request.setType(desired.type());
                request.setLine(line);
                request.setSearchMode("COMBINATION");
                request.setPageNumber((long) page);
                request.setPageSize((long) DnsProviderSupport.PAGE_SIZE);
                DescribeDomainRecordsResponse response = send(client, request, deadline);
                List<DescribeDomainRecordsResponse.Record> records = response.getDomainRecords() == null ? List.of() : response.getDomainRecords();
                for (var record : records) {
                    if (DnsProviderSupport.equal(domain, record.getDomainName()) && DnsProviderSupport.equal(rr, record.getRR()) &&
                        DnsProviderSupport.equal(desired.type(), record.getType()) && Objects.equals(line, record.getLine())) matches.add(record);
                }
                if (records.size() < DnsProviderSupport.PAGE_SIZE || (response.getTotalCount() != null &&
                    (long) page * DnsProviderSupport.PAGE_SIZE >= response.getTotalCount())) break;
            }
            var existing = DnsProviderSupport.select(matches, desired, DescribeDomainRecordsResponse.Record::getRecordId);
            if (existing == null) {
                operation = "创建解析记录";
                AddDomainRecordRequest request = new AddDomainRecordRequest();
                request.setDomainName(domain); request.setRR(rr); request.setType(desired.type());
                request.setValue(desired.value()); request.setTTL((long) desired.ttl()); request.setLine(line);
                var response = send(client, request, deadline);
                if (response.getRecordId() == null || response.getRecordId().isBlank()) throw new DnsProviderException("阿里云未返回新记录 ID");
                return new DnsSyncResult(response.getRecordId(), desired.value(), "CREATED");
            }
            if (existing.getStatus() != null && !existing.getStatus().equalsIgnoreCase("ENABLE"))
                throw new DnsProviderException("阿里云解析记录已暂停，请先在服务商启用该记录");
            if (DnsProviderSupport.sameValue(existing.getValue(), desired.value()) && Objects.equals(existing.getTTL(), (long) desired.ttl()))
                return new DnsSyncResult(existing.getRecordId(), desired.value(), "UNCHANGED");
            UpdateDomainRecordRequest request = new UpdateDomainRecordRequest();
            operation = "更新解析记录";
            request.setRecordId(existing.getRecordId()); request.setRR(rr); request.setType(desired.type());
            request.setValue(desired.value()); request.setTTL((long) desired.ttl()); request.setLine(line);
            send(client, request, deadline);
            return new DnsSyncResult(existing.getRecordId(), desired.value(), "UPDATED");
        } catch (DnsProviderException exception) { throw exception; }
        catch (ClientException exception) { throw AliyunDnsErrors.request(operation, exception, credentials); }
        catch (RuntimeException exception) { throw AliyunDnsErrors.runtime(operation, client == null); }
        finally { if (client != null) try { client.shutdown(); } catch (RuntimeException ignored) { } }
    }
}
