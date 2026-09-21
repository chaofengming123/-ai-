package com.example.aiknowledge.exception;

public class RegistrationException extends RuntimeException {
    public enum Kind { INVALID_INPUT, CONFLICT }
    private final Kind kind;
    public RegistrationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
