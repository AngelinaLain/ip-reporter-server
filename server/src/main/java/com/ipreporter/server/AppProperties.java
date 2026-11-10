package com.ipreporter.server;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotBlank @Size(max=128) String adminUsername, @NotBlank @Size(min=12, max=256) String adminPassword,
                            @NotBlank @Size(min=16, max=4096) String reportToken,
                            @NotBlank String encryptionKey, @Min(30) int offlineSeconds,
                            @Min(30) int syncSeconds, boolean schedulingEnabled, boolean trustProxyHeaders) {
    @Override public String toString() { return "AppProperties[credentials=REDACTED]"; }
}
