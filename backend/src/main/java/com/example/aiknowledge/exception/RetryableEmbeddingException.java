package com.example.aiknowledge.exception;

// 只标识上游明确返回的暂时性 HTTP 故障，不以对外错误码推断可重试性。
public class RetryableEmbeddingException extends ChatException {
    public RetryableEmbeddingException() { super(502,"向量服务暂时不可用，请稍后重试。"); }
}
