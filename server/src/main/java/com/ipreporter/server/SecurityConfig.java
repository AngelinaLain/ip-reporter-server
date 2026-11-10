package com.ipreporter.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean AuthenticationManager authenticationManager(AppProperties properties) {
        var encoder=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        var users=new InMemoryUserDetailsManager(User.withUsername(properties.adminUsername()).password(encoder.encode(properties.adminPassword())).roles("ADMIN").build());
        var provider=new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties properties, SecurityContextRepository repository) throws Exception {
        http.securityContext(context->context.securityContextRepository(repository))
            .csrf(csrf->csrf.ignoringRequestMatchers("/api/report"))
            .authorizeHttpRequests(auth->auth.requestMatchers("/api/auth/session","/api/auth/login","/api/report","/actuator/health","/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->jsonError(response,401,"请先登录"))
                .accessDeniedHandler((request,response,error)->jsonError(response,403,"会话验证失败，请刷新页面后重试")))
            .addFilterBefore(new ReportAccessFilter(properties.reportToken()),UsernamePasswordAuthenticationFilter.class)
            .logout(logout->logout.disable())
            .formLogin(form->form.disable()).httpBasic(basic->basic.disable());
        return http.build();
    }
    static void jsonError(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\""+message+"\"}");
    }
    static final class ReportAccessFilter extends OncePerRequestFilter {
        private final byte[] expected;
        ReportAccessFilter(String token) { expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8); }
        @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().equals("/api/report"); }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
            String authorization=request.getHeader("Authorization");
            if(authorization==null || !MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8))) { jsonError(response,401,"上报凭据无效");return; }
            if(!request.getMethod().equals("POST")) { jsonError(response,405,"仅支持 POST 上报");return; }
            // 对 chunked 和 Content-Length 请求同时限制大小，防止解析器过量分配内存。
            if(request.getContentLengthLong()>262144) { jsonError(response,413,"上报内容超过 256 KiB");return; }
            byte[] body=request.getInputStream().readNBytes(262145);
            if(body.length>262144) { jsonError(response,413,"上报内容超过 256 KiB");return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input=new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public boolean isFinished() { return input.available()==0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
            },response);
        }
    }
}
