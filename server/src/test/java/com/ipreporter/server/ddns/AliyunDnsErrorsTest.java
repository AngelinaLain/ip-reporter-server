package com.ipreporter.server.ddns;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.*;
import com.aliyuncs.exceptions.ClientException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class AliyunDnsErrorsTest {
    static final DnsCredentials CREDS = new DnsCredentials("test-access-id", "test-private-secret");
    static final String REQUEST_ID = "536E9CAD-DB30-4647-AC87-AA5CC38C5382";
    static final DnsRecord RECORD = new DnsRecord("example.com", "home", "A", "192.0.2.10", 600, "default", "");

    ClientException failure(String code, String id) {
        var error = mock(ClientException.class);
        when(error.getErrCode()).thenReturn(code);
        when(error.getRequestId()).thenReturn(id);
        when(error.getMessage()).thenReturn("https://signed.invalid/?Secret=" + CREDS.accessKeySecret());
        return error;
    }

    @Test void initializationFailureDoesNotBlamePermissionsOrExposeSdkMessage() {
        var provider = new AliyunDnsProvider(credentials -> { throw new IllegalStateException("signature=" + CREDS.accessKeySecret()); });
        var error = assertThrows(DnsProviderException.class, () -> provider.upsert(CREDS, RECORD));
        assertTrue(error.getMessage().contains("初始化客户端"));
        assertTrue(error.getMessage().contains("SDK_INITIALIZATION_FAILED"));
        assertFalse(error.getMessage().contains("权限"));
        assertFalse(error.getMessage().contains(CREDS.accessKeySecret()));
        assertNull(error.getCause());
    }

    @Test void queryFailureIncludesOnlySafeCodeAndRequestId() throws Exception {
        var client = mock(IAcsClient.class);
        var original = failure("InvalidAccessKeyId", REQUEST_ID);
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenThrow(original);
        var error = assertThrows(DnsProviderException.class, () -> new AliyunDnsProvider(credentials -> client).upsert(CREDS, RECORD));
        assertTrue(error.getMessage().contains("查询解析记录"));
        assertTrue(error.getMessage().contains("InvalidAccessKeyId"));
        assertTrue(error.getMessage().contains(REQUEST_ID));
        assertFalse(error.getMessage().contains(CREDS.accessKeySecret()));
        assertFalse(error.getMessage().contains("signed.invalid"));
        assertNull(error.getCause());
        verify(client).shutdown();
    }

    @Test void createFailureIdentifiesTheFailedApiOperation() throws Exception {
        var client = mock(IAcsClient.class);
        var empty = new DescribeDomainRecordsResponse();
        empty.setDomainRecords(List.of()); empty.setTotalCount(0L);
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(empty);
        var original = failure("Forbidden.RAM", REQUEST_ID);
        when(client.getAcsResponse(any(AddDomainRecordRequest.class))).thenThrow(original);
        var error = assertThrows(DnsProviderException.class, () -> new AliyunDnsProvider(credentials -> client).upsert(CREDS, RECORD));
        assertTrue(error.getMessage().contains("创建解析记录"));
        assertTrue(error.getMessage().contains("Forbidden.RAM"));
        assertTrue(error.getMessage().contains("此 API 操作的授权"));
        verify(client, never()).getAcsResponse(any(UpdateDomainRecordRequest.class));
    }

    @Test void unsafeCodeAndRequestIdAreNotReturned() {
        var error = AliyunDnsErrors.request("查询解析记录", failure(CREDS.accessKeySecret(), "https://signed.invalid/?key=" + CREDS.accessKeyId()), CREDS);
        assertTrue(error.getMessage().contains("SDK_REQUEST_FAILED"));
        assertFalse(error.getMessage().contains(CREDS.accessKeyId()));
        assertFalse(error.getMessage().contains(CREDS.accessKeySecret()));
        assertFalse(error.getMessage().contains("signed.invalid"));
        assertFalse(error.getMessage().contains("RequestId="));
    }

    @Test void networkFailuresAreDistinguishedWithoutForwardingCauseMessages() {
        List<Throwable> causes = List.of(new UnknownHostException(CREDS.accessKeySecret()),
            new SocketTimeoutException(CREDS.accessKeySecret()), new ConnectException(CREDS.accessKeySecret()),
            new SSLHandshakeException(CREDS.accessKeySecret()));
        List<String> hints = List.of("无法解析", "超时", "无法连接", "证书信任");
        for (int index = 0; index < causes.size(); index++) {
            var original = failure("SDK.ServerUnreachable", null);
            when(original.getCause()).thenReturn(causes.get(index));
            var message = AliyunDnsErrors.request("查询解析记录", original, CREDS).getMessage();
            assertTrue(message.contains(hints.get(index)), message);
            assertFalse(message.contains(CREDS.accessKeySecret()));
            assertFalse(message.contains("权限"));
        }
    }
}
