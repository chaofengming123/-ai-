package com.example.aiknowledge.exception;

public class ChatException extends RuntimeException {
    private final int status;
    public ChatException(int status,String message) { super(message); this.status=status; }
    public int status() { return status; }
}
