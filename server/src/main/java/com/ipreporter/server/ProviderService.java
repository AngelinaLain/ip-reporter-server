package com.ipreporter.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ipreporter.server.ddns.DnsCredentials;

@Service
public class ProviderService {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    public ProviderService(JdbcTemplate jdbc,SecretCipher cipher) { this.jdbc=jdbc;this.cipher=cipher; }
    public record Input(@NotBlank @Size(max=128) String name,@NotBlank @Pattern(regexp="ALIYUN|TENCENT|CLOUDFLARE") String type,
        @NotBlank @Size(max=512) String accessKeyId,@Size(max=4096) String accessKeySecret) {}
    public record View(long id,String name,String type,String accessKeyId,boolean configured) {}
    private record Stored(long id,String name,String type,String accessKeyId,String encryptedSecret) { View view() { return new View(id,name,type,accessKeyId,true); } }
    private Stored map(ResultSet row,int index) throws SQLException { return new Stored(row.getLong("id"),row.getString("name"),row.getString("type"),row.getString("access_key_id"),row.getString("encrypted_secret")); }
    private Stored stored(long id) { return jdbc.query("SELECT * FROM dns_providers WHERE id=?",this::map,id).stream().findFirst().orElseThrow(()->ApiException.missing("DNS 服务商不存在")); }
    public List<View> list() { return jdbc.query("SELECT * FROM dns_providers ORDER BY id DESC",this::map).stream().map(Stored::view).toList(); }
    public View get(long id) { return stored(id).view(); }
    public DnsCredentials credentials(long id) { var data=stored(id);return new DnsCredentials(data.accessKeyId(),cipher.decrypt(data.encryptedSecret())); }
    @Transactional public View create(Input input) {
        if(input.accessKeySecret()==null || input.accessKeySecret().isBlank()) throw ApiException.bad("首次创建服务商必须填写 Secret / API Token");
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO dns_providers(name,type,access_key_id,encrypted_secret,created_at) VALUES(?,?,?,?,?)",new String[]{"id"});
            statement.setString(1,input.name());statement.setString(2,input.type());statement.setString(3,input.accessKeyId());statement.setString(4,cipher.encrypt(input.accessKeySecret()));statement.setObject(5,OffsetDateTime.now(ZoneOffset.UTC));return statement;},key);
        return get(key.getKey().longValue());
    }
    @Transactional public View update(long id,Input input) {
        var original=stored(id);
        boolean supplied=input.accessKeySecret()!=null && !input.accessKeySecret().isBlank();
        if(!input.type().equals(original.type()) && !supplied) throw ApiException.bad("更换服务商类型时必须填写新的 Secret / API Token");
        if(!input.type().equals(original.type()) && jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE provider_id=?",Long.class,id)>0) throw ApiException.bad("服务商正被 DDNS 规则使用，不能更改类型");
        jdbc.update("UPDATE dns_providers SET name=?,type=?,access_key_id=?,encrypted_secret=? WHERE id=?",input.name(),input.type(),input.accessKeyId(),supplied?cipher.encrypt(input.accessKeySecret()):original.encryptedSecret(),id);
        return get(id);
    }
    @Transactional public void delete(long id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ddns_rules WHERE provider_id=?",Long.class,id)>0) throw ApiException.bad("请先删除关联 DDNS 规则");
        if(jdbc.update("DELETE FROM dns_providers WHERE id=?",id)==0) throw ApiException.missing("DNS 服务商不存在");
    }
}
