package com.example.aiknowledge.model;

// 内部存储定位信息，不直接返回浏览器。
public record StoredFile(String backend, String bucket, String key) {}
