package com.example.aiknowledge.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aiknowledge.config.TokenConfig;
import com.example.aiknowledge.entity.UserEntity;
import com.example.aiknowledge.mapper.UserMapper;
import com.example.aiknowledge.model.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final UserMapper mapper;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final String dummyHash;

    public LoginService(UserMapper mapper, PasswordEncoder passwords, JwtEncoder tokens) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String username, String password) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]{3,32}") || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid();
        }
        UserEntity user = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, normalized));
        // 用户不存在时也做一次密码校验，避免直接跳过昂贵计算。
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) throw invalid();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(TokenConfig.ISSUER)
                .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(900))
                .id(UUID.randomUUID().toString()).build();
        String token = tokens.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", 900, new UserResponse(user.getId(), user.getUsername()));
    }

    public UserResponse currentUser(Jwt jwt) {
        long id;
        try { id = Long.parseLong(jwt.getSubject()); }
        catch (RuntimeException error) { throw invalid(); }
        UserEntity user = mapper.selectById(id);
        if (user == null) throw invalid();
        return new UserResponse(user.getId(), user.getUsername());
    }

    private BadCredentialsException invalid() {
        return new BadCredentialsException("用户名或密码不正确，或账号已不可用。");
    }
}
