package com.ipreporter.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SsoConfigurationTest {
    @Test
    void claimAllowListSupportsScalarAndGroupClaims() {
        assertThat(SsoConfiguration.allowed(Map.of("email", "admin@example.com"), "email", List.of("admin@example.com"))).isTrue();
        assertThat(SsoConfiguration.allowed(Map.of("groups", List.of("users", "ip-admins")), "groups", List.of("ip-admins"))).isTrue();
        assertThat(SsoConfiguration.allowed(Map.of("groups", List.of("users")), "groups", List.of("ip-admins"))).isFalse();
        assertThat(SsoConfiguration.allowed(Map.of(), "", List.of())).isTrue();
    }
}
