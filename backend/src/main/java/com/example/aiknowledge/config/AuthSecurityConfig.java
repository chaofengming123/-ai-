package com.example.aiknowledge.config;

import jakarta.servlet.http.HttpServletResponse;
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
    SecurityFilterChain authSecurity(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, error) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"请登录，或重新登录后再试。\"}");
        };
        // 两组接口均检查身份；注册与登录仍允许匿名。
        http.securityMatcher("/api/auth/**", "/api/knowledge-bases", "/api/knowledge-bases/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                .authenticationEntryPoint(unauthorized));
        return http.build();
    }
}
