package com.ipreporter.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public record ReportInput(@JsonProperty("schema_version") @NotNull @Min(1) @Max(1) Integer schemaVersion,
    @JsonProperty("agent_version") @NotBlank @Size(max=64) String agentVersion,
    @JsonProperty("device_id") @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,128}") String deviceId,
    @NotNull Instant timestamp,@NotNull @Min(0) Long sequence,
    @NotBlank @Pattern(regexp="startup|address_change|heartbeat|manual") String event,
    @NotBlank @Size(max=255) String hostname,@NotBlank @Size(max=64) String os,
    @NotNull @Size(max=1024) List<@NotBlank @Size(max=64) String> ipv4,
    @NotNull @Size(max=1024) List<@NotBlank @Size(max=64) String> ipv6,
    @NotNull @Size(max=128) List<@Valid InterfaceInput> interfaces,
    @JsonProperty("uptime_seconds") @NotNull @Min(0) Long uptimeSeconds,
    @NotBlank @Pattern(regexp="alive") String status) {
    public record InterfaceInput(@Size(max=256) String id,@NotBlank @Size(max=256) String name,
        @Size(max=64) String mac,@NotNull @Size(max=128) List<@NotBlank @Size(max=64) String> ipv4,
        @NotNull @Size(max=128) List<@NotBlank @Size(max=64) String> ipv6) {}
}
