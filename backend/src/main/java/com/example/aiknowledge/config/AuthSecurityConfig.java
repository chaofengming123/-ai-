package com.example.aiknowledge.config;

import jakarta.servlet.http.HttpServletResponse;
import com.example.aiknowledge.mapper.UserMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.util.List;
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
    JwtAuthenticationConverter databaseRoles(UserMapper users) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var user = users.selectById(Long.parseLong(jwt.getSubject()));
            if (user == null) throw new InvalidBearerTokenException("Account unavailable");
            return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        });
        return converter;
    }

    @Bean
    SecurityFilterChain authSecurity(HttpSecurity http, JwtAuthenticationConverter databaseRoles) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, error) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"请登录，或重新登录后再试。\"}");
        };
        AccessDeniedHandler forbidden = (request, response, error) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"当前账号没有管理知识库的权限，请联系管理员。\"}");
        };
        // 角色以数据库当前值为准，JWT 只证明身份。
        http.securityMatcher("/api/auth/**", "/api/knowledge-bases", "/api/knowledge-bases/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/knowledge-bases", "/api/knowledge-bases/**").authenticated()
                .requestMatchers(HttpMethod.HEAD, "/api/knowledge-bases", "/api/knowledge-bases/**").authenticated()
                .requestMatchers("/api/knowledge-bases", "/api/knowledge-bases/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(databaseRoles))
                .authenticationEntryPoint(unauthorized));
        return http.build();
    }
}
