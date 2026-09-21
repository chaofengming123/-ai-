package com.example.aiknowledge.model;

import java.time.LocalDateTime;

// API 不暴露内部存储位置；文件内容不放进这个响应。
public record DocumentInfo(long id, long knowledgeBaseId, String fileName,
        String fileType, long fileSize, String status, LocalDateTime createdAt) {}
