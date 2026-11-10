package com.ipreporter.server;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MachineService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AppProperties properties;
    public MachineService(JdbcTemplate jdbc,ObjectMapper json,AppProperties properties) { this.jdbc=jdbc;this.json=json;this.properties=properties; }
    public record MachineView(String deviceId,String hostname,String os,String agentVersion,Instant lastSeen,
        Instant reportedAt,String event,long sequence,long uptimeSeconds,boolean online,String peerIp,
        List<String> ipv4,List<String> ipv6,List<ReportInput.InterfaceInput> interfaces) {}
    // 使用服务器接收时间计算在线状态；设备时钟漂移不影响服务存活判断。
    public void accept(ReportInput input,String peerIp) {
        var normalized=normalize(input);
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        String snapshot=json.writeValueAsString(normalized);
        int updated=jdbc.update("UPDATE machines SET hostname=?,os=?,agent_version=?,last_seen=?,peer_ip=?,snapshot=? WHERE device_id=?",
            normalized.hostname(),normalized.os(),normalized.agentVersion(),now,peerIp,snapshot,normalized.deviceId());
        if(updated==0) {
            try { jdbc.update("INSERT INTO machines(device_id,hostname,os,agent_version,last_seen,peer_ip,snapshot) VALUES(?,?,?,?,?,?,?)",
                normalized.deviceId(),normalized.hostname(),normalized.os(),normalized.agentVersion(),now,peerIp,snapshot); }
            catch(DuplicateKeyException race) { jdbc.update("UPDATE machines SET hostname=?,os=?,agent_version=?,last_seen=?,peer_ip=?,snapshot=? WHERE device_id=?",
                normalized.hostname(),normalized.os(),normalized.agentVersion(),now,peerIp,snapshot,normalized.deviceId()); }
        }
    }
    private ReportInput normalize(ReportInput input) {
        input.ipv4().forEach(ip->IpAddresses.parse(ip,"A"));input.ipv6().forEach(ip->IpAddresses.parse(ip,"AAAA"));
        var ids=new HashSet<String>();var interfaces=new ArrayList<ReportInput.InterfaceInput>();
        var all4=new TreeSet<String>();var all6=new TreeSet<String>();
        for(var item:input.interfaces()) {
            String id=item.id()==null || item.id().isBlank()?item.name():item.id();
            if(!ids.add(id)) throw ApiException.bad("网卡标识重复，请升级客户端或使用唯一网卡名称");
            String mac=item.mac()==null?"":item.mac().toLowerCase(Locale.ROOT);
            if(!mac.isEmpty() && !mac.matches("[0-9a-f]{2}(?::[0-9a-f]{2}){5,19}")) throw ApiException.bad("MAC 地址格式错误");
            item.ipv4().forEach(ip->IpAddresses.parse(ip,"A"));item.ipv6().forEach(ip->IpAddresses.parse(ip,"AAAA"));
            List<String> ipv4=item.ipv4().stream().distinct().sorted().toList(),ipv6=item.ipv6().stream().distinct().sorted().toList();
            all4.addAll(ipv4);all6.addAll(ipv6);interfaces.add(new ReportInput.InterfaceInput(id,item.name(),mac,ipv4,ipv6));
        }
        return new ReportInput(input.schemaVersion(),input.agentVersion(),input.deviceId(),input.timestamp(),input.sequence(),input.event(),input.hostname(),input.os(),
            List.copyOf(all4),List.copyOf(all6),List.copyOf(interfaces),input.uptimeSeconds(),input.status());
    }
    private MachineView map(ResultSet row,int index) throws SQLException {
        var snapshot=json.readValue(row.getString("snapshot"),ReportInput.class);
        Instant lastSeen=row.getObject("last_seen",OffsetDateTime.class).toInstant();
        return new MachineView(snapshot.deviceId(),snapshot.hostname(),snapshot.os(),snapshot.agentVersion(),lastSeen,snapshot.timestamp(),snapshot.event(),snapshot.sequence(),snapshot.uptimeSeconds(),
            lastSeen.isAfter(Instant.now().minusSeconds(properties.offlineSeconds())),row.getString("peer_ip"),snapshot.ipv4(),snapshot.ipv6(),snapshot.interfaces());
    }
    public List<MachineView> list() { return jdbc.query("SELECT * FROM machines ORDER BY last_seen DESC",this::map); }
    public MachineView get(String id) { return jdbc.query("SELECT * FROM machines WHERE device_id=?",this::map,id).stream().findFirst().orElseThrow(()->ApiException.missing("机器不存在")); }
    @Transactional public void delete(String id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE machine_device_id=?",Long.class,id)>0) throw ApiException.bad("请先删除该机器关联的 DDNS 规则");
        if(jdbc.update("DELETE FROM machines WHERE device_id=?",id)==0) throw ApiException.missing("机器不存在");
    }
}
