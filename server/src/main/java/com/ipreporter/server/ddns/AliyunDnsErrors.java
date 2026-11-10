package com.ipreporter.server.ddns;

import com.aliyuncs.exceptions.ClientException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/** 诊断只包含固定提示、白名单错误码和请求 ID，不返回 SDK 原文或签名 URL。 */
final class AliyunDnsErrors {
    private AliyunDnsErrors() {}

    static DnsProviderException runtime(String operation, boolean initializing) {
        String code = initializing ? "SDK_INITIALIZATION_FAILED" : "SDK_EXECUTION_FAILED";
        String hint = initializing ? "请检查服务端 SDK 配置与依赖，升级到修复版本" : "请检查服务端 SDK 与响应处理";
        return new DnsProviderException("阿里云 DNS " + operation + "失败（" + code + "），" + hint);
    }

    static DnsProviderException request(String operation, ClientException error, DnsCredentials credentials) {
        String code = safe(error.getErrCode(), "[A-Za-z0-9_.-]{1,120}", credentials);
        if (code.isEmpty()) code = "SDK_REQUEST_FAILED";
        String requestId = safe(error.getRequestId(), "[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", credentials);
        String reference = requestId.isEmpty() ? "" : "，RequestId=" + requestId;
        return new DnsProviderException("阿里云 DNS " + operation + "失败（" + code + "）" + reference + "，" + hint(code, error));
    }

    private static String safe(String value, String pattern, DnsCredentials credentials) {
        if (value == null || !value.matches(pattern)) return "";
        if ((!credentials.accessKeyId().isEmpty() && value.contains(credentials.accessKeyId())) ||
            (!credentials.accessKeySecret().isEmpty() && value.contains(credentials.accessKeySecret()))) return "";
        return value;
    }

    private static String hint(String code, Throwable error) {
        // 只按异常类型区分网络故障；异常 message 可能包含认证参数，不能透传。
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof SSLException) return "请检查服务端证书信任、系统时间和 HTTPS 代理";
            if (cause instanceof UnknownHostException) return "服务端无法解析阿里云 API 域名，请检查 DNS 和网络出口";
            if (cause instanceof SocketTimeoutException) return "访问阿里云 API 超时，请检查服务端网络出口";
            if (cause instanceof ConnectException) return "无法连接阿里云 API，请检查防火墙、代理和网络出口";
        }
        String value = code.toLowerCase(Locale.ROOT);
        if (value.contains("invalidaccesskey") || value.contains("signature")) return "请核对 AccessKey ID、Secret 和服务端时间";
        if (value.contains("forbidden") || value.contains("nopermission") || value.contains("unauthorized"))
            return "请核对当前 AccessKey 对该域名和此 API 操作的授权";
        if (value.contains("ttl")) return "请核对域名套餐允许的 TTL 范围";
        if (value.contains("line")) return "请核对域名支持的解析线路";
        if (value.contains("throttl") || value.contains("quota") || value.contains("limit"))
            return "请核对 API 调用频率和解析记录配额";
        return "请根据错误码检查 API 参数和服务端连通性";
    }
}
