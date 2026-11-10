package com.ipreporter.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ipreporter.server.ddns.AliyunDnsProvider;
import com.ipreporter.server.ddns.DnsProviderException;
import com.ipreporter.server.ddns.DnsRecord;
import com.ipreporter.server.ddns.DnsSyncResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MachineService machines;
    @Autowired
    ProviderService providers;
    @Autowired
    DdnsRuleService rules;
    @Autowired
    SecretCipher cipher;
    @MockitoBean(answers = org.mockito.Answers.CALLS_REAL_METHODS)
    AliyunDnsProvider aliyun;
    static final String TOKEN = "test-report-token-123456789";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ddns_logs");
        jdbc.update("DELETE FROM ddns_rules");
        jdbc.update("DELETE FROM dns_providers");
        jdbc.update("DELETE FROM machine_device_aliases");
        jdbc.update("DELETE FROM machines");
        when(aliyun.type()).thenReturn("ALIYUN");
        doAnswer(call -> new DnsSyncResult("remote-123", ((DnsRecord) call.getArgument(1)).value(), "UPDATED")).when(aliyun).upsert(any(), any());
    }

    Map<String, Object> report(boolean metadata) {
        Map<String, Object> iface = metadata ? Map.of("id", "adapter-guid-1", "name", "以太网", "mac", "AA:BB:CC:DD:EE:FF", "ipv4", List.of("8.8.8.8", "10.1.2.3"), "ipv6", List.of("2409:1234::10")) :
                Map.of("name", "以太网", "ipv4", List.of("8.8.8.8", "10.1.2.3"), "ipv6", List.of("2409:1234::10"));
        return Map.ofEntries(Map.entry("schema_version", 1), Map.entry("agent_version", "1.1.0"), Map.entry("device_id", "test-machine"), Map.entry("timestamp", "2026-11-10T01:02:03Z"), Map.entry("sequence", 1), Map.entry("event", "manual"), Map.entry("hostname", "测试电脑"), Map.entry("os", "windows"), Map.entry("ipv4", List.of("8.8.8.8", "10.1.2.3")), Map.entry("ipv6", List.of("2409:1234::10")), Map.entry("interfaces", List.of(iface)), Map.entry("uptime_seconds", 10), Map.entry("status", "alive"));
    }

    void sendReport(boolean metadata) throws Exception {
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report(metadata)))).andExpect(status().isNoContent());
    }

    ProviderService.View provider() {
        return providers.create(new ProviderService.Input("测试阿里云", "ALIYUN", "test-key", "very-secret-cloud-key"));
    }

    DdnsRuleService.Input input(long providerId, boolean privateAddress) {
        return new DdnsRuleService.Input("home DDNS", "test-machine", "adapter-guid-1", "以太网", "A", providerId, "Example.COM.", "home", 600, "default", "", "AUTO", "", privateAddress, false);
    }

    @Test
    void managementRequiresSessionAndCsrf() throws Exception {
        mvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());
        var initial = mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false)).andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        String csrf = json.readTree(initial.getResponse().getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"test-admin-password-12345\"}")).andExpect(status().isForbidden());
        String originalId = session.getId();
        var login = mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"test-admin-password-12345\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get("/api/machines").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAdminStillCannotForgeAgentReport() throws Exception {
        mvc.perform(post("/api/report").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report(true)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/report").header("Authorization", "Bearer wrong").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsPersistMetadataAndIgnoreSpoofedPeerHeader() throws Exception {
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).header("X-Forwarded-For", "1.2.3.4").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report(true)))).andExpect(status().isNoContent());
        var machine = machines.get("test-machine");
        assertThat(machine.online()).isTrue();
        assertThat(machine.peerIp()).isEqualTo("127.0.0.1");
        assertThat(machine.hostname()).isEqualTo("测试电脑");
        assertThat(machine.interfaces().getFirst().mac()).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(machine.interfaces().getFirst().id()).isEqualTo("adapter-guid-1");
        assertThat(machine.lastSeen()).isAfter(Instant.now().minusSeconds(10));
        sendReport(true);
        assertThat(machines.list()).hasSize(1);
    }

    @Test
    void legacyReportsWithoutMacAndIdRemainCompatible() throws Exception {
        sendReport(false);
        var iface = machines.get("test-machine").interfaces().getFirst();
        assertThat(iface.id()).isEqualTo("以太网");
        assertThat(iface.mac()).isEmpty();
    }

    @Test
    void invalidFamilyAndDuplicateAdapterAreRejected() throws Exception {
        var payload = new java.util.HashMap<>(report(true));
        payload.put("ipv4", List.of("localhost"));
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andExpect(status().isBadRequest());
        payload = new java.util.HashMap<>(report(true));
        var iface = ((List<?>) payload.get("interfaces")).getFirst();
        payload.put("interfaces", List.of(iface, iface));
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andExpect(status().isBadRequest());
        assertThat(machines.list()).isEmpty();
    }

    @Test
    void oversizedReportIsRejectedBeforeParsing() throws Exception {
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(262145))).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void credentialEncryptionIsAuthenticatedAndWriteOnly() throws Exception {
        var provider = provider();
        String stored = jdbc.queryForObject("SELECT encrypted_secret FROM dns_providers WHERE id=?", String.class, provider.id());
        assertThat(stored).doesNotContain("very-secret-cloud-key");
        assertThat(cipher.decrypt(stored)).isEqualTo("very-secret-cloud-key");
        String tampered = stored.substring(0, stored.length() - 4) + "AAAA";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        mvc.perform(get("/api/providers").with(user("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("very-secret-cloud-key"))));
        providers.update(provider.id(), new ProviderService.Input("保留 Secret", "ALIYUN", "new-id", ""));
        assertThat(providers.credentials(provider.id()).accessKeySecret()).isEqualTo("very-secret-cloud-key");
    }

    @Test
    void syncUsesSelectedAdapterAndPublishesOnlyGlobalByDefault() throws Exception {
        sendReport(true);
        var provider = provider();
        var rule = rules.create(input(provider.id(), false));
        assertThat(rule.enabled()).isFalse();
        assertThat(rule.domain()).isEqualTo("example.com");
        var synced = rules.sync(rule.id());
        assertThat(synced.lastStatus()).isEqualTo("SUCCESS");
        assertThat(synced.lastAddress()).isEqualTo("8.8.8.8");
        assertThat(synced.recordId()).isEqualTo("remote-123");
        verify(aliyun).upsert(any(), argThat(record -> record.value().equals("8.8.8.8") && record.rr().equals("home")));
        assertThat(rules.logs(10)).hasSize(1);
        assertThat(rules.logs(10).getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    void offlineMachineNeverChangesDns() throws Exception {
        sendReport(true);
        var rule = rules.create(input(provider().id(), false));
        jdbc.update("UPDATE machines SET last_seen=?", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        assertThat(rules.sync(rule.id()).lastStatus()).isEqualTo("SKIPPED");
        verify(aliyun, never()).upsert(any(), any());
    }

    @Test
    void missingSpecificAddressNeverChangesDns() throws Exception {
        sendReport(true);
        long providerId = provider().id();
        var base = input(providerId, false);
        var rule = rules.create(new DdnsRuleService.Input(base.name(), base.machineDeviceId(), base.interfaceId(), base.interfaceName(), "A", providerId, base.domain(), base.rr(), 600, "default", "", "SPECIFIC", "8.8.8.8", false, false));
        var payload = new java.util.HashMap<>(report(true));
        payload.put("interfaces", List.of(Map.of("id", "adapter-guid-1", "name", "renamed adapter", "mac", "aa:bb:cc:dd:ee:ff", "ipv4", List.of("10.1.2.3"), "ipv6", List.of())));
        mvc.perform(post("/api/report").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andExpect(status().isNoContent());
        assertThat(rules.sync(rule.id()).lastStatus()).isEqualTo("SKIPPED");
        verify(aliyun, never()).upsert(any(), any());
    }

    @Test
    void duplicateDnsTargetsAndReferencedDeletionAreRejected() throws Exception {
        sendReport(true);
        var provider = provider();
        var input = input(provider.id(), false);
        rules.create(input);
        assertThatThrownBy(() -> rules.create(input)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> providers.delete(provider.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> machines.delete("test-machine")).isInstanceOf(ApiException.class);
    }

    @Test
    void cloudErrorsBecomeBoundedUiLogWithoutCredentialLeak() throws Exception {
        sendReport(true);
        var rule = rules.create(input(provider().id(), false));
        doThrow(new DnsProviderException("阿里云权限不足")).when(aliyun).upsert(any(), any());
        var result = rules.sync(rule.id());
        assertThat(result.lastStatus()).isEqualTo("ERROR");
        assertThat(result.lastMessage()).isEqualTo("阿里云权限不足");
        assertThat(json.writeValueAsString(rules.logs(10))).doesNotContain("very-secret-cloud-key");
    }

    @Test
    void scheduledSyncIgnoresDisabledRules() throws Exception {
        sendReport(true);
        rules.create(input(provider().id(), false));
        rules.scheduledSync();
        verify(aliyun, never()).upsert(any(), any());
    }

    @Test
    void privateAddressRequiresExplicitOptIn() {
        assertThat(IpAddresses.eligible("10.1.2.3", "A", false)).isFalse();
        assertThat(IpAddresses.eligible("100.90.1.2", "A", false)).isFalse();
        assertThat(IpAddresses.eligible("10.1.2.3", "A", true)).isTrue();
        assertThat(IpAddresses.eligible("127.0.0.1", "A", true)).isFalse();
        assertThat(IpAddresses.eligible("fd7a:115c::1", "AAAA", false)).isFalse();
        assertThat(IpAddresses.eligible("2409:1234::10", "AAAA", false)).isTrue();
        assertThatThrownBy(() -> IpAddresses.parse("example.com", "AAAA")).isInstanceOf(ApiException.class);
    }

    @Test
    void proxyPeerAddressRequiresExplicitTrustAndSingleIpLiteral() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Real-IP", "8.8.8.8");
        assertThat(MachineController.peerIp(request, false)).isEqualTo("172.18.0.2");
        assertThat(MachineController.peerIp(request, true)).isEqualTo("8.8.8.8");
        request.removeHeader("X-Real-IP");
        request.addHeader("X-Real-IP", "8.8.8.8, 1.1.1.1");
        assertThat(MachineController.peerIp(request, true)).isEqualTo("172.18.0.2");
        request.removeHeader("X-Real-IP");
        request.addHeader("X-Real-IP", "malicious.example");
        assertThat(MachineController.peerIp(request, true)).isEqualTo("172.18.0.2");
    }
}
