package com.ipreporter.server.ddns;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import okhttp3.OkHttpClient;

final class DnsProviderSupport {
    private DnsProviderSupport() { }
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 20;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10))
        .writeTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(12))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    static String domain(DnsRecord record) {
        try {
            String value = IDN.toASCII(Objects.requireNonNull(record.domain()).strip(), IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (value.length() > 253 || !value.contains(".") || value.contains("..")) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) { throw new DnsProviderException("域名格式无效"); }
    }

    static String rr(DnsRecord record) {
        if (record.rr() == null || record.rr().isBlank()) return "@";
        String value = record.rr().strip().toLowerCase(Locale.ROOT);
        if (!value.equals("@") && !value.matches("(?:\\*\\.)?[a-z0-9_*-]+(?:\\.[a-z0-9_-]+)*"))
            throw new DnsProviderException("主机记录格式无效");
        if (value.length() > 253) throw new DnsProviderException("主机记录过长");
        return value;
    }

    static String fqdn(DnsRecord record) { return rr(record).equals("@") ? domain(record) : rr(record) + "." + domain(record); }
    static String line(DnsRecord record, String fallback) {
        if (record.line() == null || record.line().isBlank() || record.line().equals("default")) return fallback;
        return record.line().strip();
    }
    static boolean equal(String left, String right) { return left != null && right != null && left.equalsIgnoreCase(right); }
    static boolean hasRecordId(DnsRecord record) { return record.recordId() != null && !record.recordId().isBlank(); }

    static void validate(DnsCredentials credentials, DnsRecord record) {
        if (credentials == null || credentials.accessKeyId() == null || credentials.accessKeySecret() == null ||
            credentials.accessKeyId().isBlank() || credentials.accessKeySecret().isBlank())
            throw new DnsProviderException("DNS 服务商凭据不完整");
        if (record == null) throw new DnsProviderException("缺少目标 DNS 记录");
        domain(record); rr(record);
        if (!"A".equals(record.type()) && !"AAAA".equals(record.type())) throw new DnsProviderException("仅支持 A / AAAA 记录");
        if (record.ttl() < 60 || record.ttl() > 86400) throw new DnsProviderException("TTL 必须在 60..86400 秒之间");
        try {
            InetAddress address = literal(record.value());
            if ((record.type().equals("A") && !(address instanceof Inet4Address)) ||
                (record.type().equals("AAAA") && !(address instanceof Inet6Address))) throw new IllegalArgumentException();
        } catch (Exception exception) { throw new DnsProviderException("记录值与 A / AAAA 地址类型不匹配"); }
    }

    private static InetAddress literal(String value) throws Exception {
        if (value == null || !(value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}") ||
            (value.contains(":") && value.matches("[a-fA-F0-9:.]+")))) throw new IllegalArgumentException();
        return InetAddress.getByName(value);
    }

    static boolean sameValue(String current, String desired) {
        try { return Arrays.equals(literal(current).getAddress(), literal(desired).getAddress()); }
        catch (Exception ignored) { return false; }
    }

    // 存在多个同名记录时要求明确 ID，避免 DDNS 覆盖负载均衡或其他应用的解析。
    static <T> T select(List<T> matches, DnsRecord desired, Function<T, String> id) {
        if (hasRecordId(desired)) return matches.stream().filter(item -> desired.recordId().equals(id.apply(item)))
            .findFirst().orElseThrow(() -> new DnsProviderException("指定的记录 ID 不属于所选域名、主机记录、类型或线路"));
        if (matches.size() > 1) throw new DnsProviderException("存在多个匹配记录，请填写明确的记录 ID");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static DnsProviderException remoteError(String provider, String code) {
        String safeCode = code != null && code.matches("[A-Za-z0-9_.-]{1,120}") ? code : "REQUEST_FAILED";
        return new DnsProviderException(provider + " 调用失败（" + safeCode + "），请核对凭据、权限、TTL 和配额");
    }

    static long operationDeadline() { return System.nanoTime() + Duration.ofSeconds(30).toNanos(); }
    static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) throw new DnsProviderException("DNS 同步超过时间预算，稍后重试");
    }
}
