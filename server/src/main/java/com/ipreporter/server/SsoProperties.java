package com.ipreporter.server;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.sso")
public record SsoProperties(boolean enabled, String registrationId, String providerName,
                            String issuerUri, String clientId, String clientSecret,
                            String redirectUri, List<String> scopes, String adminClaim, List<String> adminValues) {
    public SsoProperties {
        registrationId = blankDefault(registrationId, "sso");
        providerName = blankDefault(providerName, "企业 SSO");
        redirectUri = blankDefault(redirectUri, "{baseUrl}/login/oauth2/code/{registrationId}");
        scopes = scopes == null || scopes.isEmpty() ? List.of("openid", "profile", "email") : List.copyOf(scopes);
        adminClaim = adminClaim == null ? "" : adminClaim.trim();
        adminValues = adminValues == null ? List.of() : adminValues.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
