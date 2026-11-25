package com.ipreporter.server;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCredentialService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final PasswordEncoder encoder;

    public AdminCredentialService(JdbcTemplate jdbc, AppProperties properties, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.encoder = encoder;
    }

    @PostConstruct
    void initialize() {
        jdbc.update("INSERT INTO admin_credentials(username,password_hash,updated_at) " +
                        "SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM admin_credentials)",
                properties.adminUsername(), encoder.encode(properties.adminPassword()), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return jdbc.query("SELECT username,password_hash FROM admin_credentials WHERE username=?",
                        (row, index) -> User.withUsername(row.getString("username"))
                                .password(row.getString("password_hash")).roles("ADMIN").build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("管理员账户不存在"));
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        UserDetails admin = loadUserByUsername(username);
        if (!encoder.matches(currentPassword, admin.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (encoder.matches(newPassword, admin.getPassword())) {
            throw ApiException.bad("新密码不能与当前密码相同");
        }
        if (jdbc.update("UPDATE admin_credentials SET password_hash=?,updated_at=? WHERE username=?",
                encoder.encode(newPassword), OffsetDateTime.now(ZoneOffset.UTC), username) != 1) {
            throw new IllegalStateException("管理员凭据更新失败");
        }
    }
}
