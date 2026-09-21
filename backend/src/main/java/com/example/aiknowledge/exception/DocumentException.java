package com.example.aiknowledge.exception;

public class DocumentException extends RuntimeException {
    public enum Kind { INVALID_INPUT, TOO_LARGE, STORAGE_FAILURE }
    private final Kind kind;
    public DocumentException(Kind kind, String message) { super(message); this.kind = kind; }
    public Kind kind() { return kind; }
}
