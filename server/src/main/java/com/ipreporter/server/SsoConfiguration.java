package com.ipreporter.server;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@Configuration
@ConditionalOnProperty(prefix = "app.sso", name = "enabled", havingValue = "true")
public class SsoConfiguration {
    @Bean
    InMemoryClientRegistrationRepository ssoClientRegistrationRepository(SsoProperties properties) {
        require(properties.issuerUri(), "SSO_ISSUER_URI");
        require(properties.clientId(), "SSO_CLIENT_ID");
        require(properties.clientSecret(), "SSO_CLIENT_SECRET");
        var registration = ClientRegistrations.fromIssuerLocation(properties.issuerUri().trim())
                .registrationId(properties.registrationId())
                .clientName(properties.providerName())
                .clientId(properties.clientId().trim())
                .clientSecret(properties.clientSecret())
                .redirectUri(properties.redirectUri())
                .scope(properties.scopes())
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> ssoOidcUserService(SsoProperties properties) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            if (!allowed(user.getClaims(), properties.adminClaim(), properties.adminValues())) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "SSO 账户没有管理员权限");
            }
            return user;
        };
    }

    static boolean allowed(Map<String, Object> claims, String claimName, List<String> allowedValues) {
        if (claimName.isBlank() || allowedValues.isEmpty()) return true;
        Object claim = claims.get(claimName);
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch(allowedValues::contains);
        }
        return claim != null && allowedValues.contains(String.valueOf(claim));
    }

    private static void require(String value, String environmentName) {
        if (value == null || value.isBlank()) throw new IllegalStateException(environmentName + " is required when SSO is enabled");
    }
}
