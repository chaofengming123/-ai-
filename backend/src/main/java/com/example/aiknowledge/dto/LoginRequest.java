package com.example.aiknowledge.dto;

public record LoginRequest(String username, String password) {
    @Override
    public String toString() { return "LoginRequest[credentials=REDACTED]"; }
}
