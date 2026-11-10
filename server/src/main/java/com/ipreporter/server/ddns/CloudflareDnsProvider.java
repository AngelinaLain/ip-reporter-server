package com.ipreporter.server.ddns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class CloudflareDnsProvider implements DnsProvider {
    @FunctionalInterface
    interface Transport { JsonObject send(String method, String path, String token, JsonObject body); }
    private final Transport transport;
    public CloudflareDnsProvider() { this(CloudflareDnsProvider::request); }
    CloudflareDnsProvider(Transport transport) { this.transport = transport; }
    @Override public String type() { return "CLOUDFLARE"; }

    private static JsonObject request(String method, String path, String token, JsonObject body) {
        RequestBody content = body == null ? null : RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url("https://api.cloudflare.com/client/v4" + path)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json")
            .method(method, content).build();
        try (Response response = DnsProviderSupport.HTTP.newCall(request).execute()) {
            if (response.body() == null) throw DnsProviderSupport.remoteError("Cloudflare", "EMPTY_RESPONSE");
            // 不在内存中无界读取远端响应，也不把供应商原始错误正文输出到日志。
            byte[] bytes = response.body().byteStream().readNBytes(DnsProviderSupport.MAX_RESPONSE_BYTES + 1);
            if (bytes.length > DnsProviderSupport.MAX_RESPONSE_BYTES) throw DnsProviderSupport.remoteError("Cloudflare", "RESPONSE_TOO_LARGE");
            JsonObject result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!response.isSuccessful() || !result.has("success") || !result.get("success").getAsBoolean()) {
                String code = "HTTP_" + response.code();
                if (result.has("errors") && result.getAsJsonArray("errors").size() > 0) {
                    JsonObject first = result.getAsJsonArray("errors").get(0).getAsJsonObject();
                    if (first.has("code")) code = first.get("code").getAsString();
                }
                throw DnsProviderSupport.remoteError("Cloudflare", code);
            }
            return result;
        } catch (DnsProviderException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw DnsProviderSupport.remoteError("Cloudflare", "REQUEST_FAILED"); }
    }

    @Override public DnsSyncResult upsert(DnsCredentials credentials, DnsRecord desired) {
        DnsProviderSupport.validate(credentials, desired);
        String zone = credentials.accessKeyId();
        if (!zone.matches("[a-fA-F0-9]{32}")) throw new DnsProviderException("Cloudflare Zone ID 必须为 32 位十六进制 ID");
        long deadline = DnsProviderSupport.operationDeadline();
        String prefix = "/zones/" + zone;
        try {
            // 先校验区域归属；不能把另一个区域中恰好相似的记录当作目标记录。
            JsonObject zoneInfo = transport.send("GET", prefix, credentials.accessKeySecret(), null).getAsJsonObject("result");
            if (zoneInfo == null || !zoneInfo.has("name") || !DnsProviderSupport.equal(DnsProviderSupport.domain(desired), zoneInfo.get("name").getAsString()))
                throw new DnsProviderException("Cloudflare Zone ID 与规则中的主域名不匹配");
            String fqdn = DnsProviderSupport.fqdn(desired);
            List<JsonObject> matches = new ArrayList<>();
            for (int page = 1; ; page++) {
                DnsProviderSupport.checkDeadline(deadline);
                if (page > DnsProviderSupport.MAX_PAGES) throw new DnsProviderException("Cloudflare 记录数量超过单次安全查询上限");
                String path = prefix + "/dns_records?type=" + desired.type() + "&name=" +
                    URLEncoder.encode(fqdn, StandardCharsets.UTF_8) + "&per_page=" + DnsProviderSupport.PAGE_SIZE + "&page=" + page;
                JsonObject response = transport.send("GET", path, credentials.accessKeySecret(), null);
                JsonArray records = response.getAsJsonArray("result");
                if (records == null) throw DnsProviderSupport.remoteError("Cloudflare", "INVALID_RESPONSE");
                for (JsonElement item : records) {
                    JsonObject record = item.getAsJsonObject();
                    if (record.has("name") && record.has("type") && DnsProviderSupport.equal(fqdn, record.get("name").getAsString()) &&
                        DnsProviderSupport.equal(desired.type(), record.get("type").getAsString())) matches.add(record);
                }
                JsonObject info = response.getAsJsonObject("result_info");
                Integer pages = info != null && info.has("total_pages") ? info.get("total_pages").getAsInt() : null;
                if ((pages != null && page >= pages) || records.size() < DnsProviderSupport.PAGE_SIZE) break;
            }
            JsonObject existing = DnsProviderSupport.select(matches, desired, record -> record.get("id").getAsString());
            DnsProviderSupport.checkDeadline(deadline);
            JsonObject payload = new JsonObject();
            payload.addProperty("content", desired.value()); payload.addProperty("ttl", desired.ttl());
            if (existing == null) {
                payload.addProperty("type", desired.type()); payload.addProperty("name", fqdn); payload.addProperty("proxied", false);
                JsonObject result = transport.send("POST", prefix + "/dns_records", credentials.accessKeySecret(), payload).getAsJsonObject("result");
                if (result == null || !result.has("id")) throw DnsProviderSupport.remoteError("Cloudflare", "INVALID_RESPONSE");
                return new DnsSyncResult(result.get("id").getAsString(), desired.value(), "CREATED");
            }
            String id = existing.get("id").getAsString();
            if (!id.matches("[a-fA-F0-9]{32}")) throw DnsProviderSupport.remoteError("Cloudflare", "INVALID_RECORD_ID");
            if (existing.has("proxied") && existing.get("proxied").getAsBoolean())
                throw new DnsProviderException("Cloudflare DDNS 仅支持 DNS only 记录，请先在服务商关闭代理");
            if (DnsProviderSupport.sameValue(existing.get("content").getAsString(), desired.value()) && existing.get("ttl").getAsInt() == desired.ttl())
                return new DnsSyncResult(id, desired.value(), "UNCHANGED");
            // PATCH 只改地址和 TTL，保留现有备注、标签等非 DDNS 字段。
            transport.send("PATCH", prefix + "/dns_records/" + id, credentials.accessKeySecret(), payload);
            return new DnsSyncResult(id, desired.value(), "UPDATED");
        } catch (DnsProviderException exception) { throw exception; }
        catch (RuntimeException exception) { throw DnsProviderSupport.remoteError("Cloudflare", "INVALID_RESPONSE"); }
    }
}
