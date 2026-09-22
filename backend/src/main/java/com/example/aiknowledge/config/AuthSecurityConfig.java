package com.example.aiknowledge.config;

import jakarta.servlet.http.HttpServletResponse;
import com.example.aiknowledge.mapper.UserMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.access.AccessDeniedHandler;
import com.example.aiknowledge.mapper.UserAccessMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AuthSecurityConfig {
    @Bean
    JwtAuthenticationConverter databasePermissions(UserMapper users, UserAccessMapper access) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var user = users.selectById(Long.parseLong(jwt.getSubject()));
            if (user == null) throw new InvalidBearerTokenException("Account unavailable");
            return access.permissions(user.getId()).stream()
                    .map(code -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority(code)).toList();
        });
        return converter;
    }

    @Bean
    SecurityFilterChain authSecurity(HttpSecurity http, JwtAuthenticationConverter databasePermissions) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, error) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"请登录，或重新登录后再试。\"}");
        };
        AccessDeniedHandler forbidden = (request, response, error) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"当前账号没有执行此操作的权限，请联系管理员。\"}");
        };
        // 角色以数据库当前值为准，JWT 只证明身份。
        http.securityMatcher("/api/auth/**", "/api/knowledge-bases", "/api/knowledge-bases/**", "/api/documents", "/api/documents/**", "/api/chat", "/api/chat/**", "/api/embeddings", "/api/embeddings/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/embeddings/config").hasAuthority("chat:send")
                .requestMatchers(HttpMethod.POST, "/api/embeddings/compare").hasAuthority("chat:send")
                .requestMatchers(HttpMethod.GET, "/api/embeddings/stored").hasAuthority("chat:send")
                .requestMatchers(HttpMethod.POST, "/api/embeddings/stored", "/api/embeddings/stored/search").hasAuthority("chat:send")
                .requestMatchers("/api/embeddings", "/api/embeddings/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/chat/config").hasAuthority("chat:send")
                .requestMatchers(HttpMethod.POST, "/api/chat", "/api/chat/stream").hasAuthority("chat:send")
                .requestMatchers("/api/chat", "/api/chat/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/documents").hasAuthority("document:read")
                .requestMatchers(HttpMethod.GET, "/api/documents/*/download").hasAuthority("document:read")
                .requestMatchers(HttpMethod.GET, "/api/documents/*/text").hasAuthority("document:read")
                .requestMatchers(HttpMethod.GET, "/api/documents/*/chunks").hasAuthority("document:read")
                .requestMatchers(HttpMethod.GET, "/api/documents/*/index").hasAuthority("document:read")
                .requestMatchers(HttpMethod.POST, "/api/documents/*/index").hasAuthority("document:index")
                .requestMatchers(HttpMethod.POST, "/api/documents/*/search").access(
                    new org.springframework.security.web.access.expression.WebExpressionAuthorizationManager(
                        "hasAuthority('document:read') and hasAuthority('chat:send')"))
                .requestMatchers(HttpMethod.POST, "/api/documents").hasAuthority("document:upload")
                .requestMatchers("/api/documents", "/api/documents/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/knowledge-bases", "/api/knowledge-bases/**").hasAuthority("knowledge-base:read")
                .requestMatchers(HttpMethod.HEAD, "/api/knowledge-bases", "/api/knowledge-bases/**").hasAuthority("knowledge-base:read")
                .requestMatchers(HttpMethod.POST, "/api/knowledge-bases").hasAuthority("knowledge-base:create")
                .requestMatchers(HttpMethod.PUT, "/api/knowledge-bases/*").hasAuthority("knowledge-base:update")
                .requestMatchers(HttpMethod.DELETE, "/api/knowledge-bases/*").hasAuthority("knowledge-base:delete")
                .requestMatchers("/api/knowledge-bases", "/api/knowledge-bases/**").denyAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(databasePermissions))
                .authenticationEntryPoint(unauthorized));
        return http.build();
    }
}
