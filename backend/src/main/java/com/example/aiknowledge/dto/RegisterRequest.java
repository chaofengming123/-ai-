package com.example.aiknowledge.dto;

public record RegisterRequest(String username, String password) {
    // 防止调试时直接输出 DTO 泄露密码；不要记录请求正文。
    @Override
    public String toString() { return "RegisterRequest[credentials=REDACTED]"; }
}
