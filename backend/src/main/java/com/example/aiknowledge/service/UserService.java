package com.example.aiknowledge.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.example.aiknowledge.entity.UserEntity;
import com.example.aiknowledge.mapper.UserMapper;
import com.example.aiknowledge.model.UserResponse;
import com.example.aiknowledge.exception.RegistrationException;
import static com.example.aiknowledge.exception.RegistrationException.Kind.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper mapper, PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(String username, String password) {
        String normalizedUsername = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!normalizedUsername.matches("[a-z0-9_]{3,32}")) {
            throw new RegistrationException(INVALID_INPUT, "用户名需要 3–32 位，只能包含英文字母、数字和下划线。");
        }
        // 密码不 trim：空格也是用户输入的一部分。BCrypt 上限按 UTF-8 字节计算。
        if (password == null || password.isBlank()
                || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new RegistrationException(INVALID_INPUT, "密码至少 12 个字符，不能全为空白，UTF-8 编码最多 72 字节。");
        }
        UserEntity entity = new UserEntity();
        entity.setUsername(normalizedUsername);
        entity.setPasswordHash(passwordEncoder.encode(password));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException error) {
            throw new RegistrationException(CONFLICT, "这个用户名已被使用，请换一个用户名。");
        }
        return new UserResponse(entity.getId(), entity.getUsername());
    }
}
