package com.example.aiknowledge.model;

// 对外响应不含密码或密码哈希。
public record UserResponse(long id, String username) {}
