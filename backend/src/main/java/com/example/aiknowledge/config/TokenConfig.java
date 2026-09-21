package com.example.aiknowledge.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class TokenConfig {
    public static final String ISSUER = "ai-knowledge-local";

    @Bean
    SecretKey signingKey(@Value("${app.jwt.secret}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT signing key must contain at least 32 bytes.");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, com.example.aiknowledge.mapper.UserMapper users) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtClaimValidator<String> existingUser = new JwtClaimValidator<>("sub", subject -> {
            try {
                return subject != null && users.selectById(Long.parseLong(subject)) != null;
            } catch (NumberFormatException error) {
                return false;
            }
        });
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), existingUser));
        return decoder;
    }
}
