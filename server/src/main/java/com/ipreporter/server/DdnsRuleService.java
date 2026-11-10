package com.ipreporter.server;

import com.ipreporter.server.ddns.*;
import jakarta.validation.constraints.*;
import java.net.IDN;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DdnsRuleService {
    private final JdbcTemplate jdbc;
    private final MachineService machines;
    private final ProviderService providers;
    private final Map<String,DnsProvider> adapters;
    private final AppProperties properties;
    private final Map<Long,ReentrantLock> locks=new ConcurrentHashMap<>();
    public DdnsRuleService(JdbcTemplate jdbc,MachineService machines,ProviderService providers,List<DnsProvider> adapters,AppProperties properties) {
        this.jdbc=jdbc;this.machines=machines;this.providers=providers;this.properties=properties;
        var map=new HashMap<String,DnsProvider>();for(var adapter:adapters) map.put(adapter.type(),adapter);this.adapters=Map.copyOf(map);
    }
    public record Input(@NotBlank @Size(max=128) String name,@NotBlank @Size(max=128) String machineDeviceId,
        @Size(max=256) String interfaceId,@Size(max=256) String interfaceName,
        @NotBlank @Pattern(regexp="A|AAAA") String recordType,@NotNull @Min(1) Long providerId,
        @NotBlank @Size(max=253) String domain,@NotBlank @Size(max=253) String rr,@Min(60) @Max(86400) int ttl,
        @Size(max=128) String line,@Size(max=128) String recordId,
        @NotBlank @Pattern(regexp="AUTO|SPECIFIC") String addressMode,@Size(max=64) String specificAddress,
        boolean allowPrivate,boolean enabled) {}
    public record View(long id,String name,String machineDeviceId,String interfaceId,String interfaceName,String recordType,long providerId,
        String domain,String rr,int ttl,String line,String recordId,String addressMode,String specificAddress,boolean allowPrivate,boolean enabled,
        String lastStatus,String lastMessage,String lastAddress,Instant lastSyncAt) {}
    public record Log(long id,Long ruleId,String ruleName,String status,String action,String address,String message,Instant createdAt) {}
    private static String empty(String input) { return input==null?"":input.trim(); }
    private static Instant instant(ResultSet row,String column) throws SQLException { var value=row.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant(); }
    private View map(ResultSet r,int index) throws SQLException { return new View(r.getLong("id"),r.getString("name"),r.getString("machine_device_id"),r.getString("interface_id"),r.getString("interface_name"),r.getString("record_type"),r.getLong("provider_id"),r.getString("domain"),r.getString("rr"),r.getInt("ttl"),r.getString("line"),r.getString("record_id"),r.getString("address_mode"),r.getString("specific_address"),r.getBoolean("allow_private"),r.getBoolean("enabled"),r.getString("last_status"),r.getString("last_message"),r.getString("last_address"),instant(r,"last_sync_at")); }
    public List<View> list() { return jdbc.query("SELECT * FROM ddns_rules ORDER BY id DESC",this::map); }
    public View get(long id) { return jdbc.query("SELECT * FROM ddns_rules WHERE id=?",this::map,id).stream().findFirst().orElseThrow(()->ApiException.missing("DDNS 规则不存在")); }
    private Input normalize(Input input) {
        var machine=machines.get(input.machineDeviceId());var provider=providers.get(input.providerId());
        if(!adapters.containsKey(provider.type())) throw ApiException.bad("尚未实现此 DNS 服务商");
        String domain=domain(input.domain());String rr=relativeName(input.rr());
        if(!rr.equals("@") && rr.length()+1+domain.length()>253) throw ApiException.bad("完整 DNS 名称长度超过 253 字符");
        String interfaceId=empty(input.interfaceId()),interfaceName=empty(input.interfaceName());
        if(interfaceId.isEmpty() && interfaceName.isEmpty()) throw ApiException.bad("请选择来源网卡");
        var matches=machine.interfaces().stream().filter(i->!interfaceId.isEmpty()?interfaceId.equals(i.id()):interfaceName.equals(i.name())).toList();
        if(matches.size()!=1) throw ApiException.bad("网卡不存在或名称不唯一，请使用唯一网卡标识");
        var selected=matches.getFirst();
        String specific=empty(input.specificAddress());
        if(input.addressMode().equals("SPECIFIC")) {
            IpAddresses.parse(specific,input.recordType());
            var candidates=input.recordType().equals("A")?selected.ipv4():selected.ipv6();
            if(!candidates.contains(specific)) throw ApiException.bad("指定 IP 当前不属于选中网卡");
            if(!IpAddresses.eligible(specific,input.recordType(),input.allowPrivate())) throw ApiException.bad("所选地址不适合发布；私网/保留地址需要显式允许");
        } else specific="";
        String line=empty(input.line());
        if(provider.type().equals("CLOUDFLARE")) line="default";
        else if(provider.type().equals("TENCENT") && (line.isEmpty() || line.equals("default") || line.equals("默认"))) line="默认";
        else if(provider.type().equals("ALIYUN") && (line.isEmpty() || line.equals("默认"))) line="default";
        return new Input(input.name().trim(),input.machineDeviceId(),selected.id(),selected.name(),input.recordType(),input.providerId(),domain,rr,input.ttl(),line,empty(input.recordId()),input.addressMode(),specific,input.allowPrivate(),input.enabled());
    }
    private static String domain(String input) {
        String value=empty(input).toLowerCase(Locale.ROOT);
        if(value.endsWith(".")) value=value.substring(0,value.length()-1);
        try { value=IDN.toASCII(value,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch(IllegalArgumentException error) { throw ApiException.bad("域名格式错误"); }
        if(value.length()>253 || !value.contains(".") || !value.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) throw ApiException.bad("请填写托管根域名，例如 example.com");
        for(String label:value.split("\\.",-1)) if(label.isEmpty() || label.length()>63 || label.startsWith("-") || label.endsWith("-")) throw ApiException.bad("域名格式错误");
        return value;
    }
    private static String relativeName(String input) {
        String value=empty(input).toLowerCase(Locale.ROOT);
        if(value.equals("@")) return value;
        if((!value.equals("*") && !value.matches("(?:\\*\\.)?[a-z0-9_](?:[a-z0-9_.-]*[a-z0-9_])?")) || value.length()>253 || value.contains("..")) throw ApiException.bad("主机记录应填写 @、home 或多级相对名称");
        for(String label:value.split("\\.")) if(label.length()>63 || label.startsWith("-") || label.endsWith("-")) throw ApiException.bad("主机记录格式错误");
        return value;
    }
    public View create(Input raw) {
        Input i=normalize(raw);var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var s=connection.prepareStatement("INSERT INTO ddns_rules(name,machine_device_id,interface_id,interface_name,record_type,provider_id,domain,rr,ttl,line,record_id,address_mode,specific_address,allow_private,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            s.setString(1,i.name());s.setString(2,i.machineDeviceId());s.setString(3,i.interfaceId());s.setString(4,i.interfaceName());s.setString(5,i.recordType());s.setLong(6,i.providerId());s.setString(7,i.domain());s.setString(8,i.rr());s.setInt(9,i.ttl());s.setString(10,i.line());s.setString(11,i.recordId());s.setString(12,i.addressMode());s.setString(13,i.specificAddress());s.setBoolean(14,i.allowPrivate());s.setBoolean(15,i.enabled());return s;},key);
        return get(key.getKey().longValue());
    }
    public View update(long id,Input raw) {
        ReentrantLock lock=lock(id);try {
            get(id);Input i=normalize(raw);
            jdbc.update("UPDATE ddns_rules SET name=?,machine_device_id=?,interface_id=?,interface_name=?,record_type=?,provider_id=?,domain=?,rr=?,ttl=?,line=?,record_id=?,address_mode=?,specific_address=?,allow_private=?,enabled=?,last_status=NULL,last_message=NULL,last_address=NULL,last_sync_at=NULL WHERE id=?",
                i.name(),i.machineDeviceId(),i.interfaceId(),i.interfaceName(),i.recordType(),i.providerId(),i.domain(),i.rr(),i.ttl(),i.line(),i.recordId(),i.addressMode(),i.specificAddress(),i.allowPrivate(),i.enabled(),id);
            return get(id);
        } finally { lock.unlock(); }
    }
    public void delete(long id) {
        ReentrantLock lock=lock(id);try { if(jdbc.update("DELETE FROM ddns_rules WHERE id=?",id)==0) throw ApiException.missing("DDNS 规则不存在"); }
        finally { lock.unlock();locks.remove(id,lock); }
    }
    // 即使来源网卡已经消失，也允许暂停规则，并保留之前的同步结果。
    public View setEnabled(long id, boolean enabled) {
        ReentrantLock lock = lock(id);
        try {
            if (jdbc.update("UPDATE ddns_rules SET enabled=? WHERE id=?", enabled, id) == 0)
                throw ApiException.missing("DDNS 规则不存在");
            return get(id);
        } finally {
            lock.unlock();
        }
    }
    private ReentrantLock lock(long id) {
        get(id); // 无效 ID 不进入锁表，避免错误请求累积常驻对象。
        var lock=locks.computeIfAbsent(id,key->new ReentrantLock());
        if(!lock.tryLock()) throw new ApiException(HttpStatus.CONFLICT,"此规则正在同步，请稍后重试");return lock;
    }
    // 仅在来源机器在线且选中网卡仍存在时发布；地址消失时不删除远端 DNS。
    public String selectAddress(View rule) {
        var machine=machines.get(rule.machineDeviceId());
        if(!machine.online()) throw ApiException.bad("机器已离线，保留现有 DNS 记录");
        var matches=machine.interfaces().stream().filter(i->!rule.interfaceId().isBlank()?rule.interfaceId().equals(i.id()):rule.interfaceName().equals(i.name())).toList();
        if(matches.size()!=1) throw ApiException.bad("来源网卡缺失或名称不唯一，保留现有 DNS 记录");
        var iface=matches.getFirst();var addresses=rule.recordType().equals("A")?iface.ipv4():iface.ipv6();
        if(rule.addressMode().equals("SPECIFIC")) {
            if(!addresses.contains(rule.specificAddress()) || !IpAddresses.eligible(rule.specificAddress(),rule.recordType(),rule.allowPrivate())) throw ApiException.bad("指定 IP 已不可用，保留现有 DNS 记录");
            return rule.specificAddress();
        }
        // 多地址时选取稳定的字典序首个候选；需要固定地址时可在页面改为指定 IP。
        return addresses.stream().filter(ip->IpAddresses.eligible(ip,rule.recordType(),rule.allowPrivate())).sorted().findFirst()
            .orElseThrow(()->ApiException.bad("网卡没有可发布的对应地址，请检查地址族或私网选项"));
    }
    public View sync(long id) {
        return sync(id, false);
    }

    private View sync(long id, boolean scheduled) {
        ReentrantLock lock=lock(id);try {
            View rule=get(id);String address;
            // 定时任务取出列表后，规则可能已被管理员暂停。
            if (scheduled && !rule.enabled()) return rule;
            try { address=selectAddress(rule); }
            catch(ApiException error) { record(rule,"SKIPPED","SKIPPED",null,error.getMessage());return get(id); }
            try {
                var provider=providers.get(rule.providerId());var adapter=adapters.get(provider.type());
                if(adapter==null) throw new IllegalStateException("Provider unavailable");
                var result=adapter.upsert(providers.credentials(rule.providerId()),new DnsRecord(rule.domain(),rule.rr(),rule.recordType(),address,rule.ttl(),rule.line(),rule.recordId()));
                jdbc.update("UPDATE ddns_rules SET record_id=? WHERE id=?",result.recordId(),id);
                record(rule,"SUCCESS",result.action(),address,result.action().equals("UNCHANGED")?"DNS 记录已是当前地址":"DNS 记录同步成功");
            } catch(DnsProviderException error) { record(rule,"ERROR","FAILED",address,safe(error.getStackTrace().toString())); }
            catch(Exception error) { record(rule,"ERROR","FAILED",address,"同步失败，请检查凭据、域名权限及服务商 API 连通性"); }
            return get(id);
        } finally { lock.unlock(); }
    }
    private static String safe(String message) { return message==null?"DNS 服务商调用失败":message.substring(0,Math.min(message.length(),512)); }
    private void record(View rule,String status,String action,String address,String message) {
        var now=OffsetDateTime.now(ZoneOffset.UTC);message=safe(message);
        // 同步失败或跳过时保留最后一次成功发布的地址，尝试地址单独记入日志。
        jdbc.update("UPDATE ddns_rules SET last_status=?,last_message=?,last_address=?,last_sync_at=? WHERE id=?",
            status,message,"SUCCESS".equals(status) ? address : rule.lastAddress(),now,rule.id());
        jdbc.update("INSERT INTO ddns_logs(rule_id,rule_name,status,action,address,message,created_at) VALUES(?,?,?,?,?,?,?)",rule.id(),rule.name(),status,action,address,message,now);
        jdbc.update("DELETE FROM ddns_logs WHERE id NOT IN(SELECT id FROM ddns_logs ORDER BY id DESC LIMIT 1000)");
    }
    @Scheduled(fixedDelayString="${app.sync-seconds:60}000",initialDelayString="${app.sync-seconds:60}000")
    public void scheduledSync() {
        if(!properties.schedulingEnabled()) return;
        for(View rule:list()) if(rule.enabled()) {
            try { sync(rule.id(), true); } catch(Exception ignored) { /* 删除/同步竞争留给下一次调度，不停止其余规则。 */ }
        }
    }
    public List<Log> logs(int requested) {
        int limit=Math.clamp(requested,1,200);
        return jdbc.query("SELECT * FROM ddns_logs ORDER BY id DESC LIMIT ?",(r,index)->new Log(r.getLong("id"),(Long)r.getObject("rule_id"),r.getString("rule_name"),r.getString("status"),r.getString("action"),r.getString("address"),r.getString("message"),instant(r,"created_at")),limit);
    }
}
