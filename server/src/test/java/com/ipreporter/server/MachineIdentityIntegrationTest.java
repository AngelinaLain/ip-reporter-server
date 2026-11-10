package com.ipreporter.server;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Timeout(60)
class MachineIdentityIntegrationTest {
    @Autowired MachineService machines;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProviderService providers;
    @Autowired DdnsRuleService rules;
    static final String TOKEN="test-report-token-123456789";
    static final List<ReportInput.InterfaceInput> ADAPTERS=List.of(
        new ReportInput.InterfaceInput("adapter-guid-1","以太网","aa:bb:cc:dd:ee:ff",List.of("8.8.8.8"),List.of("2409:1234::10")),
        new ReportInput.InterfaceInput("tunnel-guid","隧道","",List.of("10.1.2.3"),List.of()));

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM ddns_logs");jdbc.update("DELETE FROM ddns_rules");jdbc.update("DELETE FROM dns_providers");
        jdbc.update("DELETE FROM machine_device_aliases");jdbc.update("DELETE FROM machines");
    }
    ReportInput report(String id) { return report(id,"同一台电脑","windows",ADAPTERS); }
    ReportInput report(String id,String hostname,String os,List<ReportInput.InterfaceInput> adapters) {
        return new ReportInput(1,"1.1.1",id,Instant.parse("2026-11-10T01:02:03Z"),1L,"heartbeat",hostname,os,
            adapters.stream().flatMap(adapter->adapter.ipv4().stream()).toList(),adapters.stream().flatMap(adapter->adapter.ipv6().stream()).toList(),adapters,10L,"alive");
    }
    void accept(String id) { machines.accept(report(id),"192.0.2.10"); }
    DdnsRuleService.View rule(String machineId) {
        var provider=providers.create(new ProviderService.Input("测试服务商","ALIYUN","test-key","test-secret"));
        return rules.create(new DdnsRuleService.Input("来源规则",machineId,"adapter-guid-1","以太网","A",provider.id(),"example.com","home",600,"default","","AUTO","",false,false));
    }
    void assertStatus(Throwable error,HttpStatus expected) { assertThat(error).isInstanceOfSatisfying(ApiException.class,api->assertThat(api.status()).isEqualTo(expected)); }

    @Test void candidateRequiresCompleteAdapterIdentityAndChoosesLatestDeterministically() {
        accept("older");accept("newer");
        var time=OffsetDateTime.ofInstant(Instant.parse("2026-11-10T01:02:03Z"),ZoneOffset.UTC);
        jdbc.update("UPDATE machines SET last_seen=? WHERE device_id=?",time,"older");
        jdbc.update("UPDATE machines SET last_seen=? WHERE device_id=?",time.plusSeconds(1),"newer");
        var reordered=List.of(ADAPTERS.get(1),new ReportInput.InterfaceInput("adapter-guid-1","重命名网卡","aa:bb:cc:dd:ee:ff",List.of("1.1.1.1"),List.of()));
        machines.accept(report("renamed","同一台电脑","windows",reordered),"192.0.2.99");
        jdbc.update("UPDATE machines SET last_seen=? WHERE device_id=?",time.plusSeconds(1),"renamed");
        assertThat(machines.duplicates()).containsExactly(new MachineService.DuplicateGroup("newer",List.of("renamed","older")));
    }
    @Test void sameHostnameIpOrSingleSharedMacDoesNotMakeDifferentMachinesCandidates() {
        accept("first");
        machines.accept(report("different-mac","同一台电脑","windows",List.of(
            new ReportInput.InterfaceInput("adapter-guid-1","以太网","aa:bb:cc:dd:ee:01",List.of("8.8.8.8"),List.of()),ADAPTERS.get(1))),"192.0.2.10");
        machines.accept(report("missing-adapter","同一台电脑","windows",List.of(ADAPTERS.getFirst())),"192.0.2.10");
        machines.accept(report("different-os","同一台电脑","linux",ADAPTERS),"192.0.2.10");
        machines.accept(report("different-hostname","另一台电脑","windows",ADAPTERS),"192.0.2.10");
        assertThat(machines.list()).hasSize(5);assertThat(machines.duplicates()).isEmpty();
        assertThatThrownBy(()->machines.merge("first",List.of("different-mac"))).satisfies(error->assertStatus(error,HttpStatus.BAD_REQUEST));
        assertThat(machines.list()).hasSize(5);
    }
    @Test void oversizedCandidateGroupCanBeMergedInBoundedBatches() {
        for(int index=0;index<67;index++) accept("batch-"+index);
        var first=machines.duplicates().getFirst();assertThat(first.sourceDeviceIds()).hasSize(64);
        machines.merge(first.targetDeviceId(),first.sourceDeviceIds());assertThat(machines.list()).hasSize(3);
        var rest=machines.duplicates().getFirst();assertThat(rest.sourceDeviceIds()).hasSize(2);
        machines.merge(rest.targetDeviceId(),rest.sourceDeviceIds());assertThat(machines.list()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machine_device_aliases",Long.class)).isEqualTo(66);
    }
    @Test void reportsWithoutUsableMacNeverBecomeCandidates() {
        for(String mac:List.of("","00:00:00:00:00:00","ff:ff:ff:ff:ff:ff")) {
            var adapters=List.of(new ReportInput.InterfaceInput("legacy-eth0","以太网",mac,List.of("8.8.8.8"),List.of()));
            machines.accept(report("a"+mac.replace(":",""),"同一台电脑","windows",adapters),"192.0.2.10");
            machines.accept(report("b"+mac.replace(":",""),"同一台电脑","windows",adapters),"192.0.2.10");
        }
        assertThat(machines.duplicates()).isEmpty();
    }
    @Test void mergeKeepsTargetAndNewestSnapshotThenBothDeviceIdsUpdateOneMachine() {
        accept("target");
        var latest=List.of(new ReportInput.InterfaceInput("adapter-guid-1","新网卡名称","aa:bb:cc:dd:ee:ff",List.of("1.1.1.1"),List.of()),ADAPTERS.get(1));
        machines.accept(report("source","同一台电脑","windows",latest),"192.0.2.88");
        jdbc.update("UPDATE machines SET last_seen=? WHERE device_id='target'",OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        var result=machines.merge("target",List.of("source"));
        assertThat(result.deviceId()).isEqualTo("target");assertThat(result.peerIp()).isEqualTo("192.0.2.88");
        assertThat(result.interfaces().getFirst().name()).isEqualTo("新网卡名称");assertThat(result.ipv4()).contains("1.1.1.1");
        assertThat(jdbc.queryForObject("SELECT snapshot FROM machines WHERE device_id='target'",String.class)).contains("\"device_id\":\"target\"");
        assertThatThrownBy(()->machines.get("source")).satisfies(error->assertStatus(error,HttpStatus.NOT_FOUND));
        accept("source");accept("target");accept("source");
        assertThat(machines.list()).hasSize(1);assertThat(machines.list().getFirst().deviceId()).isEqualTo("target");
        assertThat(machines.duplicates()).isEmpty();
    }
    @Test void chainedMergesFlattenOldAliasesAndOldDeleteCannotDeleteRetainedMachine() {
        accept("first");accept("second");accept("third");
        machines.merge("second",List.of("first"));machines.merge("third",List.of("second"));
        assertThat(jdbc.queryForList("SELECT canonical_device_id FROM machine_device_aliases",String.class)).containsExactlyInAnyOrder("third","third");
        accept("first");accept("second");accept("third");assertThat(machines.list()).hasSize(1);
        assertThatThrownBy(()->machines.delete("first")).satisfies(error->assertStatus(error,HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->machines.merge("third",List.of("first"))).satisfies(error->assertStatus(error,HttpStatus.NOT_FOUND));
        assertThat(machines.get("third").deviceId()).isEqualTo("third");
        machines.delete("third");assertThat(machines.list()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machine_device_aliases",Long.class)).isZero();
    }
    @Test void mergePreservesDdnsBindingsAndReferencedCanonicalDeletionIsRejected() {
        accept("target");accept("source");var bound=rule("source");rules.setEnabled(bound.id(),true);
        machines.merge("target",List.of("source"));
        var migrated=rules.get(bound.id());assertThat(migrated.machineDeviceId()).isEqualTo("target");
        assertThat(migrated.interfaceId()).isEqualTo("adapter-guid-1");assertThat(migrated.enabled()).isTrue();
        assertThatThrownBy(()->machines.delete("target")).satisfies(error->assertStatus(error,HttpStatus.BAD_REQUEST));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machine_device_aliases",Long.class)).isEqualTo(1);
        rules.delete(bound.id());machines.delete("target");assertThat(machines.list()).isEmpty();
    }
    @Test void invalidDdnsMappingRejectsMergeWithoutChangingAnyRecords() {
        accept("target");accept("source");var bound=rule("source");
        jdbc.update("UPDATE ddns_rules SET interface_id='missing-interface' WHERE id=?",bound.id());
        assertThatThrownBy(()->machines.merge("target",List.of("source"))).satisfies(error->assertStatus(error,HttpStatus.BAD_REQUEST));
        assertThat(machines.list()).hasSize(2);assertThat(rules.get(bound.id()).machineDeviceId()).isEqualTo("source");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machine_device_aliases",Long.class)).isZero();
    }
    @Test void laterConstraintFailureRollsBackEarlierAliasAndDdnsWrites() {
        accept("target");accept("source-one");accept("source-two");var bound=rule("source-one");
        // 模拟历史数据中已占用的别名，使第二个来源迁移失败，验证前面的写入真正回滚。
        jdbc.update("INSERT INTO machine_device_aliases(device_id,canonical_device_id) VALUES('source-two','target')");
        assertThatThrownBy(()->machines.merge("target",List.of("source-one","source-two"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(machines.list()).hasSize(3);assertThat(rules.get(bound.id()).machineDeviceId()).isEqualTo("source-one");
        assertThat(jdbc.queryForList("SELECT device_id FROM machine_device_aliases",String.class)).containsExactly("source-two");
    }
    @Test void mergeEndpointsRequireAuthenticationCsrfAndValidSources() throws Exception {
        accept("target");accept("source");
        mvc.perform(get("/api/machines/duplicates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/machines/target/merge").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"sourceDeviceIds\":[\"source\"]}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/machines/target/merge").with(user("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"sourceDeviceIds\":[\"source\"]}")).andExpect(status().isForbidden());
        for(String body:List.of("{}","{\"sourceDeviceIds\":[]}","{\"sourceDeviceIds\":[\"source\",\"source\"]}","{\"sourceDeviceIds\":[\"target\"]}","{\"sourceDeviceIds\":[\"bad/id\"]}"))
            mvc.perform(post("/api/machines/target/merge").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/machines/target/merge").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"sourceDeviceIds\":[\"missing\"]}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/duplicates").with(user("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].sourceDeviceIds.length()").value(1));
        mvc.perform(post("/api/machines/target/merge").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"sourceDeviceIds\":[\"source\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").value("target"));
        mvc.perform(post("/api/report").header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report("source")))).andExpect(status().isNoContent());
        assertThat(machines.list()).hasSize(1);
    }
    @Test void concurrentFirstReportsCreateExactlyOneMachine() throws Exception {
        try(var workers=Executors.newFixedThreadPool(8)) {
            var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++) futures.add(workers.submit(()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();accept("concurrent");return null;}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var future:futures) future.get(30,TimeUnit.SECONDS);
        }
        assertThat(machines.list()).hasSize(1);
    }
    @Test void concurrentMergeAndOldIdReportNeverResurrectSource() throws Exception {
        try(var workers=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<12;round++) {
                String target="target-"+round,source="source-"+round;accept(target);accept(source);
                var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
                var merge=workers.submit(()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return machines.merge(target,List.of(source));});
                var report=workers.submit(()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();accept(source);return null;});
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();merge.get(30,TimeUnit.SECONDS);report.get(30,TimeUnit.SECONDS);
                accept(source);assertThat(machines.get(target).deviceId()).isEqualTo(target);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machines WHERE device_id IN (?,?)",Long.class,target,source)).isEqualTo(1);
            }
        }
        assertThat(machines.list()).hasSize(12);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM machine_device_aliases",Long.class)).isEqualTo(12);
    }
}
