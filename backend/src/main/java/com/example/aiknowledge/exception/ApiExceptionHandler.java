package com.example.aiknowledge.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorResponse(String message) { }
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ErrorResponse> handleChat(ChatException error) {
        // 流开始前的错误返回 JSON，包括只声明接收 SSE 的旧版客户端。
        return ResponseEntity.status(error.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<ErrorResponse> handleDocument(DocumentException error) {
        HttpStatus status = switch (error.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case STORAGE_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
            case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleLargeFile() {
        return ResponseEntity.status(413).body(new ErrorResponse("文件不能超过 5 MB，请求总大小不能超过 6 MB。"));
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingPart() {
        return ResponseEntity.badRequest().body(new ErrorResponse("请提供知识库编号和文件。"));
    }

    @ExceptionHandler(KnowledgeBaseException.class)
    public ResponseEntity<ErrorResponse> handleBusinessError(KnowledgeBaseException error) {
        HttpStatus status = switch (error.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("用户名或密码不正确，或账号已不可用。"));
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ErrorResponse> handleRegistrationError(RegistrationException error) {
        HttpStatus status = error.kind() == RegistrationException.Kind.CONFLICT
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson() {
        return ResponseEntity.badRequest().body(new ErrorResponse("请求体必须是格式正确的 JSON 对象。"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidId() {
        return ResponseEntity.badRequest().body(new ErrorResponse("知识库编号必须是整数。"));
    }
}
