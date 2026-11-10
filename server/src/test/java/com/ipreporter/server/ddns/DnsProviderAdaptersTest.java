package com.ipreporter.server.ddns;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.*;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.ProtocolType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.dnspod.v20210323.DnspodClient;
import com.tencentcloudapi.dnspod.v20210323.models.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DnsProviderAdaptersTest {
    private static final DnsCredentials CREDS = new DnsCredentials("test-public-id", "not-a-real-secret");
    private static final String ZONE = "11111111111111111111111111111111";
    private static final String CF_ID = "22222222222222222222222222222222";
    private static DnsRecord record(String id) { return new DnsRecord("example.com", "home", "A", "203.0.113.7", 600, null, id); }

    private static DescribeDomainRecordsResponse.Record aliRecord(String id, String value) {
        var result = new DescribeDomainRecordsResponse.Record();
        result.setDomainName("example.com"); result.setRR("home"); result.setType("A"); result.setLine("default");
        result.setRecordId(id); result.setValue(value); result.setTTL(600L);
        return result;
    }
    private static DescribeDomainRecordsResponse aliList(List<DescribeDomainRecordsResponse.Record> values, long total) {
        var response = new DescribeDomainRecordsResponse(); response.setDomainRecords(values); response.setTotalCount(total); return response;
    }
    private static RecordListItem tcRecord(long id, String value) {
        var result = new RecordListItem(); result.setRecordId(id); result.setName("home"); result.setType("A");
        result.setLine("默认"); result.setValue(value); result.setTTL(600L); return result;
    }
    private static DescribeRecordListResponse tcList(RecordListItem... values) {
        var response = new DescribeRecordListResponse(); response.setRecordList(values); return response;
    }

    @Test void aliyunCreatesOnlyWhenNoExactRecordExists() throws Exception {
        IAcsClient client = mock(IAcsClient.class);
        var unrelated = aliRecord("do-not-touch", "203.0.113.100"); unrelated.setRR("home-old");
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(aliList(List.of(unrelated), 1));
        var created = new AddDomainRecordResponse(); created.setRecordId("new-id");
        when(client.getAcsResponse(any(AddDomainRecordRequest.class))).thenReturn(created);
        DnsSyncResult result = new AliyunDnsProvider(c -> client).upsert(CREDS, record(null));
        assertEquals("CREATED", result.action()); assertEquals("new-id", result.recordId());
        var request = ArgumentCaptor.forClass(AddDomainRecordRequest.class);
        verify(client).getAcsResponse(request.capture());
        assertEquals(ProtocolType.HTTPS, request.getValue().getSysProtocol());
        assertEquals("default", request.getValue().getLine());
        verify(client, never()).getAcsResponse(any(UpdateDomainRecordRequest.class)); verify(client).shutdown();
    }

    @Test void aliyunDoesNotRewriteEquivalentIpv6() throws Exception {
        IAcsClient client = mock(IAcsClient.class);
        var existing = aliRecord("same-id", "2001:db8:0:0:0:0:0:7"); existing.setType("AAAA");
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(aliList(List.of(existing), 1));
        var desired = new DnsRecord("example.com", "home", "AAAA", "2001:db8::7", 600, "default", null);
        assertEquals("UNCHANGED", new AliyunDnsProvider(c -> client).upsert(CREDS, desired).action());
        verify(client, never()).getAcsResponse(any(AddDomainRecordRequest.class));
        verify(client, never()).getAcsResponse(any(UpdateDomainRecordRequest.class));
    }

    @Test void aliyunRefusesAmbiguityAndForeignExplicitId() throws Exception {
        IAcsClient client = mock(IAcsClient.class);
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(aliList(List.of(aliRecord("a", "203.0.113.1"),aliRecord("b", "203.0.113.2")),2));
        var provider = new AliyunDnsProvider(c -> client);
        assertThrows(DnsProviderException.class, () -> provider.upsert(CREDS, record(null)));
        assertThrows(DnsProviderException.class, () -> provider.upsert(CREDS, record("foreign")));
        verify(client, never()).getAcsResponse(any(UpdateDomainRecordRequest.class));
        when(client.getAcsResponse(any(UpdateDomainRecordRequest.class))).thenReturn(new UpdateDomainRecordResponse());
        assertEquals("b", provider.upsert(CREDS, record("b")).recordId());
        var update = ArgumentCaptor.forClass(UpdateDomainRecordRequest.class);
        verify(client).getAcsResponse(update.capture()); assertEquals("b",update.getValue().getRecordId());
    }

    @Test void aliyunPaginatesBeforeSelectingAnExactMatch() throws Exception {
        IAcsClient client = mock(IAcsClient.class);
        List<DescribeDomainRecordsResponse.Record> first = new ArrayList<>();
        for (int i=0;i<100;i++) { var unrelated=aliRecord("u"+i,"203.0.113.1"); unrelated.setLine("telecom"); first.add(unrelated); }
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(aliList(first,101),aliList(List.of(aliRecord("last","203.0.113.7")),101));
        assertEquals("last",new AliyunDnsProvider(c -> client).upsert(CREDS,record(null)).recordId());
        var queries=ArgumentCaptor.forClass(DescribeDomainRecordsRequest.class); verify(client,times(2)).getAcsResponse(queries.capture());
        assertEquals(List.of(1L,2L),queries.getAllValues().stream().map(DescribeDomainRecordsRequest::getPageNumber).toList());
    }

    @Test void aliyunErrorsDoNotExposeSdkRequestOrSecrets() throws Exception {
        IAcsClient client=mock(IAcsClient.class);
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenThrow(new ClientException("InvalidAccessKeyId", "secret="+CREDS.accessKeySecret()));
        var error=assertThrows(DnsProviderException.class,()->new AliyunDnsProvider(c->client).upsert(CREDS,record(null)));
        assertTrue(error.getMessage().contains("InvalidAccessKeyId")); assertFalse(error.getMessage().contains(CREDS.accessKeySecret())); assertNull(error.getCause());
    }

    @Test void aliyunPausedRecordIsNotSilentlyReportedHealthy() throws Exception {
        IAcsClient client=mock(IAcsClient.class);var paused=aliRecord("paused","203.0.113.7");paused.setStatus("DISABLE");
        when(client.getAcsResponse(any(DescribeDomainRecordsRequest.class))).thenReturn(aliList(List.of(paused),1));
        assertThrows(DnsProviderException.class,()->new AliyunDnsProvider(c->client).upsert(CREDS,record(null)));
        verify(client,never()).getAcsResponse(any(UpdateDomainRecordRequest.class));
    }

    @Test void tencentCreatesWithDefaultLineAndNoErrorOnEmpty() throws Exception {
        DnspodClient client=mock(DnspodClient.class);
        when(client.DescribeRecordList(any())).thenReturn(tcList());
        var created=new CreateRecordResponse();created.setRecordId(55L);when(client.CreateRecord(any())).thenReturn(created);
        var result=new TencentDnsProvider(c->client).upsert(CREDS,record(null));assertEquals("55",result.recordId());assertEquals("CREATED",result.action());
        var query=ArgumentCaptor.forClass(DescribeRecordListRequest.class);verify(client).DescribeRecordList(query.capture());
        assertEquals("no",query.getValue().getErrorOnEmpty());assertEquals("默认",query.getValue().getRecordLine());assertEquals("home",query.getValue().getSubDomain());
        var create=ArgumentCaptor.forClass(CreateRecordRequest.class);verify(client).CreateRecord(create.capture());assertEquals(600L,create.getValue().getTTL());
    }

    @Test void tencentUnchangedAndUpdatedRecordsPreserveId() throws Exception {
        DnspodClient client=mock(DnspodClient.class);
        when(client.DescribeRecordList(any())).thenReturn(tcList(tcRecord(99,"203.0.113.7")),tcList(tcRecord(99,"203.0.113.8")));
        when(client.ModifyRecord(any())).thenReturn(new ModifyRecordResponse());
        var provider=new TencentDnsProvider(c->client);
        assertEquals("UNCHANGED",provider.upsert(CREDS,record(null)).action());
        assertEquals("UPDATED",provider.upsert(CREDS,record(null)).action());
        var update=ArgumentCaptor.forClass(ModifyRecordRequest.class);verify(client).ModifyRecord(update.capture());assertEquals(99L,update.getValue().getRecordId());
        assertEquals("example.com",update.getValue().getDomain());verify(client,never()).CreateRecord(any());
    }

    @Test void tencentRefusesMultipleRecordsAndForeignId() throws Exception {
        DnspodClient client=mock(DnspodClient.class);
        when(client.DescribeRecordList(any())).thenReturn(tcList(tcRecord(1,"203.0.113.1"),tcRecord(2,"203.0.113.2")));
        var provider=new TencentDnsProvider(c->client);
        assertThrows(DnsProviderException.class,()->provider.upsert(CREDS,record(null)));
        assertThrows(DnsProviderException.class,()->provider.upsert(CREDS,record("foreign")));
        verify(client,never()).CreateRecord(any());verify(client,never()).ModifyRecord(any());
    }

    @Test void tencentPausedRecordIsNotSilentlyReenabled() throws Exception {
        DnspodClient client=mock(DnspodClient.class);var paused=tcRecord(44,"203.0.113.7");paused.setStatus("DISABLE");
        when(client.DescribeRecordList(any())).thenReturn(tcList(paused));
        assertThrows(DnsProviderException.class,()->new TencentDnsProvider(c->client).upsert(CREDS,record(null)));
        verify(client,never()).ModifyRecord(any());
    }

    @Test void tencentAddressUpdatePreservesWeightAndRemark() throws Exception {
        DnspodClient client=mock(DnspodClient.class);var existing=tcRecord(45,"203.0.113.8");existing.setWeight(30L);existing.setRemark("existing owner metadata");
        when(client.DescribeRecordList(any())).thenReturn(tcList(existing));when(client.ModifyRecord(any())).thenReturn(new ModifyRecordResponse());
        new TencentDnsProvider(c->client).upsert(CREDS,record("45"));
        var update=ArgumentCaptor.forClass(ModifyRecordRequest.class);verify(client).ModifyRecord(update.capture());
        assertEquals(30L,update.getValue().getWeight());assertEquals("existing owner metadata",update.getValue().getRemark());
    }

    @Test void tencentErrorsAreSanitizedAndValidationHappensBeforeNetwork() throws Exception {
        DnspodClient client=mock(DnspodClient.class);
        when(client.DescribeRecordList(any())).thenThrow(new TencentCloudSDKException("secret="+CREDS.accessKeySecret(),"test-request-id","AuthFailure.SecretIdNotFound"));
        var provider=new TencentDnsProvider(c->client);
        var error=assertThrows(DnsProviderException.class,()->provider.upsert(CREDS,record(null)));
        assertFalse(error.getMessage().contains(CREDS.accessKeySecret()));assertTrue(error.getMessage().contains("AuthFailure"));
        clearInvocations(client);
        assertThrows(DnsProviderException.class,()->provider.upsert(CREDS,new DnsRecord("example.com","home","A","example.org",600,null,null)));
        verifyNoInteractions(client);assertFalse(CREDS.toString().contains(CREDS.accessKeySecret()));
    }

    @Test void tencentListCountIsPageSizeNotTotalAndCannotStopPagination() throws Exception {
        DnspodClient client=mock(DnspodClient.class);
        RecordListItem[] first=new RecordListItem[100];
        for(int i=0;i<100;i++){first[i]=tcRecord(i+1,"203.0.113.1");first[i].setName("unrelated");}
        var pageOne=tcList(first);var counts=new RecordCountInfo();counts.setListCount(100L);counts.setTotalCount(101L);pageOne.setRecordCountInfo(counts);
        when(client.DescribeRecordList(any())).thenReturn(pageOne,tcList(tcRecord(999,"203.0.113.7")));
        assertEquals("999",new TencentDnsProvider(c->client).upsert(CREDS,record(null)).recordId());
        var queries=ArgumentCaptor.forClass(DescribeRecordListRequest.class);verify(client,times(2)).DescribeRecordList(queries.capture());
        assertEquals(List.of(0L,100L),queries.getAllValues().stream().map(DescribeRecordListRequest::getOffset).toList());
        verify(client,never()).CreateRecord(any());
    }

    private static JsonObject cfRecord(String id,String value) {
        JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("name","home.example.com");
        result.addProperty("type","A");result.addProperty("content",value);result.addProperty("ttl",600);result.addProperty("proxied",false);return result;
    }
    private static JsonObject cfResult(JsonObject result) { JsonObject response=new JsonObject();response.add("result",result);return response; }
    private static JsonObject cfList(List<JsonObject> records,int pages) {
        JsonObject response=new JsonObject();JsonArray values=new JsonArray();records.forEach(values::add);response.add("result",values);
        JsonObject info=new JsonObject();info.addProperty("total_pages",pages);response.add("result_info",info);return response;
    }
    private static final class CloudflareFake implements CloudflareDnsProvider.Transport {
        List<JsonObject> records=new ArrayList<>();List<String> methods=new ArrayList<>();List<JsonObject> writes=new ArrayList<>();String domain="example.com";
        @Override public JsonObject send(String method,String path,String token,JsonObject body) {
            methods.add(method+" "+path);
            if (!path.contains("dns_records")) {JsonObject zone=new JsonObject();zone.addProperty("name",domain);return cfResult(zone);}
            if(method.equals("GET")) return cfList(records,1);
            writes.add(body.deepCopy());JsonObject result=new JsonObject();result.addProperty("id",CF_ID);return cfResult(result);
        }
    }
    private static DnsCredentials cfCreds() {return new DnsCredentials(ZONE,"test-cloudflare-token");}

    @Test void cloudflareCreatesDnsOnlyAndUpdatesOnlyContentAndTtl() {
        CloudflareFake fake=new CloudflareFake();var provider=new CloudflareDnsProvider(fake);
        assertEquals("CREATED",provider.upsert(cfCreds(),record(null)).action());
        assertFalse(fake.writes.getFirst().get("proxied").getAsBoolean());assertEquals("home.example.com",fake.writes.getFirst().get("name").getAsString());
        fake.records.add(cfRecord(CF_ID,"203.0.113.8"));fake.writes.clear();
        assertEquals("UPDATED",provider.upsert(cfCreds(),record(null)).action());
        assertEquals(2,fake.writes.getFirst().size());assertTrue(fake.methods.getLast().startsWith("PATCH "));
    }

    @Test void cloudflareUnchangedDoesNotWriteAndProxyIsNeverSilentlyChanged() {
        CloudflareFake fake=new CloudflareFake();fake.records.add(cfRecord(CF_ID,"203.0.113.7"));var provider=new CloudflareDnsProvider(fake);
        assertEquals("UNCHANGED",provider.upsert(cfCreds(),record(null)).action());assertTrue(fake.writes.isEmpty());
        fake.records.getFirst().addProperty("proxied",true);
        assertThrows(DnsProviderException.class,()->provider.upsert(cfCreds(),record(null)));assertTrue(fake.writes.isEmpty());
    }

    @Test void cloudflareRejectsWrongZoneOrAmbiguousOrForeignId() {
        CloudflareFake fake=new CloudflareFake();var provider=new CloudflareDnsProvider(fake);
        fake.domain="someone-else.example";assertThrows(DnsProviderException.class,()->provider.upsert(cfCreds(),record(null)));assertEquals(1,fake.methods.size());
        fake.domain="example.com";fake.records.add(cfRecord(CF_ID,"203.0.113.1"));fake.records.add(cfRecord("33333333333333333333333333333333","203.0.113.2"));
        assertThrows(DnsProviderException.class,()->provider.upsert(cfCreds(),record(null)));
        assertThrows(DnsProviderException.class,()->provider.upsert(cfCreds(),record("foreign")));assertTrue(fake.writes.isEmpty());
        assertEquals(CF_ID,provider.upsert(cfCreds(),record(CF_ID)).recordId());
    }

    @Test void cloudflarePaginatesAndNeverAcceptsWrongRecordName() {
        List<JsonObject> first=new ArrayList<>();for(int i=0;i<100;i++){var record=cfRecord(CF_ID,"203.0.113.7");record.addProperty("name","other.example.com");first.add(record);}
        List<String> paths=new ArrayList<>();
        var provider=new CloudflareDnsProvider((method,path,token,body)->{
            paths.add(path);
            if(!path.contains("dns_records")){var zone=new JsonObject();zone.addProperty("name","example.com");return cfResult(zone);}
            return path.endsWith("page=1")?cfList(first,2):cfList(List.of(cfRecord(CF_ID,"203.0.113.7")),2);
        });
        assertEquals("UNCHANGED",provider.upsert(cfCreds(),record(null)).action());assertTrue(paths.getLast().endsWith("page=2"));
    }
}
