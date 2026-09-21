package com.example.aiknowledge.model;

import java.util.List;
// 只输出身份与权限，不输出密码哈希。
public record UserResponse(long id, String username, List<String> roles, List<String> permissions) {}
