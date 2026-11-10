package com.ipreporter.server.ddns;

/** 仅包含可安全返回前端的信息，不保留带凭据或签名 URL 的 SDK 原始异常。 */
public class DnsProviderException extends RuntimeException {
    public DnsProviderException(String safeMessage) { super(safeMessage); }
}
