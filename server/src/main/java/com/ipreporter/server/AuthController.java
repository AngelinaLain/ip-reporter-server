package com.ipreporter.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager manager;
    private final SecurityContextRepository repository;
    public AuthController(AuthenticationManager manager,SecurityContextRepository repository) { this.manager=manager;this.repository=repository; }
    public record Login(@NotBlank @Size(max=128) String username,@NotBlank @Size(max=256) String password) {}
    public record Session(boolean authenticated,String username,String csrfToken) {}
    @GetMapping("/session") Session session(Authentication authentication,HttpServletRequest request) {
        boolean loggedIn=authentication!=null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
        CsrfToken csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return new Session(loggedIn,loggedIn?authentication.getName():null,csrf.getToken());
    }
    @PostMapping("/login") Session login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        try {
            Authentication authentication=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));
            // 登录后更换 session ID，防止复用登录前的会话标识。
            if(request.getSession(false)!=null) request.changeSessionId();
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);repository.saveContext(context,request,response);
            return session(authentication,request);
        } catch(AuthenticationException error) { throw new ApiException(HttpStatus.UNAUTHORIZED,"用户名或密码错误"); }
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
    }
}
