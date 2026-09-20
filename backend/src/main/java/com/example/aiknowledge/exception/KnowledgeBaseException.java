package com.example.aiknowledge.exception;

public class KnowledgeBaseException extends RuntimeException {
    public enum Kind { INVALID_INPUT, NOT_FOUND, CONFLICT }

    private final Kind kind;

    public KnowledgeBaseException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
