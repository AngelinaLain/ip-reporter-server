package com.ipreporter.server.ddns;

/** 只在实际调用期间解密使用；覆盖 toString 防止意外日志泄密。 */
public record DnsCredentials(String accessKeyId, String accessKeySecret) {
    @Override public String toString() { return "DnsCredentials[redacted]"; }
}
