package com.example.aiknowledge.model;

import java.time.LocalDateTime;

// 内部持久化记录；Controller 使用单独的响应，不暴露集合名。
public record DocumentIndex(long documentId,String state,String attemptId,String errorMessage,
    String activeCollection,String activeModel,String activeSpace,String sourceSha256,
    int chunkCount,int dimensions,int sourceCharacters,String note,
    LocalDateTime updatedAt,LocalDateTime indexedAt) {}
