package com.ipreporter.server.ddns;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.dnspod.v20210323.DnspodClient;
import com.tencentcloudapi.dnspod.v20210323.models.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class TencentDnsProvider implements DnsProvider {
    private final Function<DnsCredentials, DnspodClient> clients;
    public TencentDnsProvider() { this(TencentDnsProvider::client); }
    TencentDnsProvider(Function<DnsCredentials, DnspodClient> clients) { this.clients = clients; }
    @Override public String type() { return "TENCENT"; }

    private static DnspodClient client(DnsCredentials credentials) {
        HttpProfile http = new HttpProfile();
        http.setEndpoint("dnspod.tencentcloudapi.com");
        http.setProtocol("https://");
        http.setConnTimeout(3); http.setReadTimeout(10); http.setWriteTimeout(10);
        // 共享有完整调用超时、禁止重定向/隐式重试的连接池，避免每次同步遗留线程。
        http.setHttpClient(DnsProviderSupport.HTTP);
        ClientProfile profile = new ClientProfile(ClientProfile.SIGN_TC3_256, http);
        profile.setDebug(false);
        return new DnspodClient(new Credential(credentials.accessKeyId(), credentials.accessKeySecret()), "", profile);
    }

    @Override public DnsSyncResult upsert(DnsCredentials credentials, DnsRecord desired) {
        DnsProviderSupport.validate(credentials, desired);
        long deadline = DnsProviderSupport.operationDeadline();
        try {
            DnspodClient client = clients.apply(credentials);
            String domain = DnsProviderSupport.domain(desired);
            String rr = DnsProviderSupport.rr(desired);
            String line = DnsProviderSupport.line(desired, "默认");
            List<RecordListItem> matches = new ArrayList<>();
            for (int page = 0; ; page++) {
                DnsProviderSupport.checkDeadline(deadline);
                if (page >= DnsProviderSupport.MAX_PAGES) throw new DnsProviderException("腾讯云记录数量超过单次安全查询上限，请缩小范围");
                DescribeRecordListRequest request = new DescribeRecordListRequest();
                request.setDomain(domain); request.setSubDomain(rr); request.setRecordType(desired.type());
                request.setRecordLine(line); request.setErrorOnEmpty("no");
                request.setLimit((long) DnsProviderSupport.PAGE_SIZE);
                request.setOffset((long) page * DnsProviderSupport.PAGE_SIZE);
                var response = client.DescribeRecordList(request);
                RecordListItem[] records = response.getRecordList() == null ? new RecordListItem[0] : response.getRecordList();
                for (var record : records) {
                    if (DnsProviderSupport.equal(rr, record.getName()) && DnsProviderSupport.equal(desired.type(), record.getType()) &&
                        Objects.equals(line, record.getLine())) matches.add(record);
                }
                // ListCount 只代表本页数量，不能拿它当总量；读取至不足一整页为止。
                if (records.length < DnsProviderSupport.PAGE_SIZE) break;
            }
            var existing = DnsProviderSupport.select(matches, desired, item -> String.valueOf(item.getRecordId()));
            DnsProviderSupport.checkDeadline(deadline);
            if (existing == null) {
                CreateRecordRequest request = new CreateRecordRequest();
                request.setDomain(domain); request.setSubDomain(rr); request.setRecordType(desired.type());
                request.setRecordLine(line); request.setValue(desired.value()); request.setTTL((long) desired.ttl());
                var response = client.CreateRecord(request);
                if (response.getRecordId() == null) throw new DnsProviderException("腾讯云未返回新记录 ID");
                return new DnsSyncResult(String.valueOf(response.getRecordId()), desired.value(), "CREATED");
            }
            if (existing.getStatus() != null && !existing.getStatus().equalsIgnoreCase("ENABLE"))
                throw new DnsProviderException("腾讯云解析记录已暂停，请先在服务商启用该记录");
            if (DnsProviderSupport.sameValue(existing.getValue(), desired.value()) && Objects.equals(existing.getTTL(), (long) desired.ttl()))
                return new DnsSyncResult(String.valueOf(existing.getRecordId()), desired.value(), "UNCHANGED");
            ModifyRecordRequest request = new ModifyRecordRequest();
            request.setDomain(domain); request.setSubDomain(rr); request.setRecordId(existing.getRecordId());
            request.setRecordType(desired.type()); request.setRecordLine(line);
            request.setValue(desired.value()); request.setTTL((long) desired.ttl());
            // 指定 ID 可能指向加权解析记录；更新地址时保留已有权重与备注。
            if (existing.getWeight() != null) request.setWeight(existing.getWeight());
            if (existing.getRemark() != null) request.setRemark(existing.getRemark());
            client.ModifyRecord(request);
            return new DnsSyncResult(String.valueOf(existing.getRecordId()), desired.value(), "UPDATED");
        } catch (DnsProviderException exception) { throw exception; }
        catch (TencentCloudSDKException exception) { throw DnsProviderSupport.remoteError("腾讯云 DNSPod", exception.getErrorCode()); }
        catch (RuntimeException exception) { throw DnsProviderSupport.remoteError("腾讯云 DNSPod", null); }
    }
}
