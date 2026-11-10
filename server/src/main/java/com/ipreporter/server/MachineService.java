package com.ipreporter.server;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
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
    public record DuplicateGroup(String targetDeviceId,List<String> sourceDeviceIds) {}
    public record MergeInput(@NotEmpty @Size(max=64) List<@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,128}") String> sourceDeviceIds) {}
    private record StoredMachine(String deviceId,ReportInput snapshot,Instant lastSeen,String peerIp) {}
    private record AdapterIdentity(String id,String mac) {}
    private record Fingerprint(String hostname,String os,List<AdapterIdentity> adapters) {}
    private record RuleBinding(long id,String machineId,String interfaceId,String interfaceName) {}
    private static final Comparator<StoredMachine> RECENT=Comparator.comparing(StoredMachine::lastSeen).reversed().thenComparing(StoredMachine::deviceId);

    // 使用服务器接收时间计算在线状态；设备时钟漂移不影响服务存活判断。
    @Transactional public void accept(ReportInput input,String peerIp) {
        var normalized=normalize(input);
        // 先锁原始设备标识的分片，避免并发首次插入和合并后旧 ID 再次创建一行。
        lockIdentities(List.of(input.deviceId()));
        for(int attempt=0;attempt<8;attempt++) {
            String canonical=canonicalId(input.deviceId());
            var existing=locked(canonical);
            // 等待行锁期间该机器可能刚被并入另一条记录，必须重新读取别名。
            if(!canonical.equals(canonicalId(input.deviceId()))) continue;
            if(existing==null && !canonical.equals(input.deviceId())) continue;
            store(withDeviceId(normalized,canonical),Instant.now(),peerIp);
            return;
        }
        throw new ApiException(HttpStatus.CONFLICT,"设备归属正在改变，请稍后重新上报");
    }
    private void lockIdentities(Collection<String> ids) {
        // 所有写入遵循分片升序、机器 ID 升序的加锁顺序，碰撞只会短暂串行化。
        ids.stream().map(id->Math.floorMod(id.hashCode(),256)).distinct().sorted().forEach(bucket->
            jdbc.queryForObject("SELECT bucket FROM machine_identity_locks WHERE bucket=? FOR UPDATE",Integer.class,bucket));
    }
    private String canonicalId(String id) {
        return jdbc.query("SELECT canonical_device_id FROM machine_device_aliases WHERE device_id=?",(row,index)->row.getString(1),id)
            .stream().findFirst().orElse(id);
    }
    private StoredMachine stored(ResultSet row,int index) throws SQLException {
        return new StoredMachine(row.getString("device_id"),json.readValue(row.getString("snapshot"),ReportInput.class),
            row.getObject("last_seen",OffsetDateTime.class).toInstant(),row.getString("peer_ip"));
    }
    private StoredMachine locked(String id) {
        return jdbc.query("SELECT * FROM machines WHERE device_id=? FOR UPDATE",this::stored,id).stream().findFirst().orElse(null);
    }
    private void store(ReportInput report,Instant seen,String peerIp) {
        var now=OffsetDateTime.ofInstant(seen,ZoneOffset.UTC);
        String snapshot=json.writeValueAsString(report);
        int updated=jdbc.update("UPDATE machines SET hostname=?,os=?,agent_version=?,last_seen=?,peer_ip=?,snapshot=? WHERE device_id=?",
            report.hostname(),report.os(),report.agentVersion(),now,peerIp,snapshot,report.deviceId());
        if(updated==0) jdbc.update("INSERT INTO machines(device_id,hostname,os,agent_version,last_seen,peer_ip,snapshot) VALUES(?,?,?,?,?,?,?)",
            report.deviceId(),report.hostname(),report.os(),report.agentVersion(),now,peerIp,snapshot);
    }
    private static ReportInput withDeviceId(ReportInput report,String id) {
        return new ReportInput(report.schemaVersion(),report.agentVersion(),id,report.timestamp(),report.sequence(),report.event(),report.hostname(),report.os(),
            report.ipv4(),report.ipv6(),report.interfaces(),report.uptimeSeconds(),report.status());
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
        return new MachineView(row.getString("device_id"),snapshot.hostname(),snapshot.os(),snapshot.agentVersion(),lastSeen,snapshot.timestamp(),snapshot.event(),snapshot.sequence(),snapshot.uptimeSeconds(),
            lastSeen.isAfter(Instant.now().minusSeconds(properties.offlineSeconds())),row.getString("peer_ip"),snapshot.ipv4(),snapshot.ipv6(),snapshot.interfaces());
    }
    public List<MachineView> list() { return jdbc.query("SELECT * FROM machines ORDER BY last_seen DESC,device_id ASC",this::map); }
    public MachineView get(String id) { return jdbc.query("SELECT * FROM machines WHERE device_id=?",this::map,id).stream().findFirst().orElseThrow(()->ApiException.missing("机器不存在")); }

    private static Fingerprint fingerprint(ReportInput report) {
        if(report.interfaces().isEmpty()) return null;
        var identities=new ArrayList<AdapterIdentity>();boolean hasMac=false;
        for(var adapter:report.interfaces()) {
            if(adapter.id()==null || adapter.id().isBlank()) return null;
            String mac=adapter.mac()==null?"":adapter.mac().toLowerCase(Locale.ROOT);
            // 全零和广播 MAC 不能作为可识别网卡的证据；IP 和网卡名称不参与身份比较。
            if(!mac.isBlank() && !mac.replace(":","").matches("0+|f+")) hasMac=true;
            identities.add(new AdapterIdentity(adapter.id(),mac));
        }
        if(!hasMac) return null;
        identities.sort(Comparator.comparing(AdapterIdentity::id).thenComparing(AdapterIdentity::mac));
        return new Fingerprint(report.hostname(),report.os(),List.copyOf(identities));
    }
    // 候选只用于提示，完整网卡标识与 MAC 集合相同也必须由管理员确认合并。
    public List<DuplicateGroup> duplicates() {
        var groups=new LinkedHashMap<Fingerprint,List<StoredMachine>>();
        for(var machine:jdbc.query("SELECT * FROM machines ORDER BY last_seen DESC,device_id ASC",this::stored)) {
            var key=fingerprint(machine.snapshot());
            if(key!=null) groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(machine);
        }
        return groups.values().stream().filter(group->group.size()>1).map(group->{
            group.sort(RECENT);return new DuplicateGroup(group.getFirst().deviceId(),group.stream().skip(1).limit(64).map(StoredMachine::deviceId).toList());
        }).toList();
    }
    @Transactional public MachineView merge(String targetId,List<String> sourceIds) {
        if(sourceIds==null || sourceIds.isEmpty() || sourceIds.size()>64 || sourceIds.stream().anyMatch(Objects::isNull)
                || sourceIds.contains(targetId) || new HashSet<>(sourceIds).size()!=sourceIds.size())
            throw ApiException.bad("请选择不同于保留机器的来源设备，且不得重复");
        var ids=new TreeSet<>(sourceIds);ids.add(targetId);lockIdentities(ids);
        var records=new TreeMap<String,StoredMachine>();
        for(String id:ids) {
            var record=locked(id);if(record==null) throw ApiException.missing("待合并的机器不存在，请刷新列表");
            records.put(id,record);
        }
        var target=records.get(targetId);var identity=fingerprint(target.snapshot());
        if(identity==null || records.values().stream().anyMatch(record->!identity.equals(fingerprint(record.snapshot()))))
            throw ApiException.bad("只允许合并主机名、系统及完整网卡标识和 MAC 集合相同的机器");
        var newest=records.values().stream().sorted(RECENT).findFirst().orElseThrow();
        var latestAdapters=new HashMap<String,ReportInput.InterfaceInput>();
        newest.snapshot().interfaces().forEach(adapter->latestAdapters.put(adapter.id(),adapter));
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var bindings=jdbc.query("SELECT id,machine_device_id,interface_id,interface_name FROM ddns_rules WHERE machine_device_id IN ("+placeholders+") ORDER BY id FOR UPDATE",
            (row,index)->new RuleBinding(row.getLong("id"),row.getString("machine_device_id"),row.getString("interface_id"),row.getString("interface_name")),ids.toArray());
        var mapped=new LinkedHashMap<Long,ReportInput.InterfaceInput>();
        for(var binding:bindings) {
            var matches=records.get(binding.machineId()).snapshot().interfaces().stream().filter(adapter->
                !binding.interfaceId().isBlank()?binding.interfaceId().equals(adapter.id()):binding.interfaceName().equals(adapter.name())).toList();
            if(matches.size()!=1 || !latestAdapters.containsKey(matches.getFirst().id()))
                throw ApiException.bad("存在无法安全映射网卡的 DDNS 规则，请先重新选择来源网卡");
            mapped.put(binding.id(),latestAdapters.get(matches.getFirst().id()));
        }
        // 所有校验完成后再迁移规则和别名；任一步失败都会回滚，不丢失已有绑定。
        for(var binding:bindings) {
            var adapter=mapped.get(binding.id());
            jdbc.update("UPDATE ddns_rules SET machine_device_id=?,interface_id=?,interface_name=? WHERE id=?",targetId,adapter.id(),adapter.name(),binding.id());
        }
        for(String source:sourceIds) {
            jdbc.update("UPDATE machine_device_aliases SET canonical_device_id=? WHERE canonical_device_id=?",targetId,source);
            jdbc.update("INSERT INTO machine_device_aliases(device_id,canonical_device_id) VALUES(?,?)",source,targetId);
            jdbc.update("DELETE FROM machines WHERE device_id=?",source);
        }
        store(withDeviceId(newest.snapshot(),targetId),newest.lastSeen(),newest.peerIp());
        return get(targetId);
    }
    @Transactional public void delete(String id) {
        lockIdentities(List.of(id));if(locked(id)==null) throw ApiException.missing("机器不存在");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE machine_device_id=?",Long.class,id)>0) throw ApiException.bad("请先删除该机器关联的 DDNS 规则");
        if(jdbc.update("DELETE FROM machines WHERE device_id=?",id)==0) throw ApiException.missing("机器不存在");
    }
}
