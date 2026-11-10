package com.ipreporter.server.ddns;

public record DnsSyncResult(String recordId, String value, String action) { }
