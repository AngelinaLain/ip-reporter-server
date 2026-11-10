package com.ipreporter.server.ddns;

/** DNS 服务商适配器；只创建或更新指定记录，不删除其他记录。 */
public interface DnsProvider {
    String type();
    DnsSyncResult upsert(DnsCredentials credentials, DnsRecord desired);
}
