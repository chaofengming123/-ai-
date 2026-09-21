package com.example.aiknowledge.service;

import com.example.aiknowledge.entity.UserEntity;
import com.example.aiknowledge.mapper.UserAccessMapper;
import com.example.aiknowledge.model.UserResponse;
import org.springframework.stereotype.Service;

@Service
public class UserAccessService {
    private final UserAccessMapper access;
    public UserAccessService(UserAccessMapper access) { this.access = access; }
    public UserResponse profile(UserEntity user) {
        return new UserResponse(user.getId(), user.getUsername(),
                access.roles(user.getId()), access.permissions(user.getId()));
    }
    public void assignDefaultRole(long userId) {
        if (access.assignRole(userId, "USER") != 1)
            throw new IllegalStateException("Default role is missing");
    }
}
