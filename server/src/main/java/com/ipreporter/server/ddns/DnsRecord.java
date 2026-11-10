package com.ipreporter.server.ddns;

public record DnsRecord(String domain, String rr, String type, String value,
                        int ttl, String line, String recordId) { }
