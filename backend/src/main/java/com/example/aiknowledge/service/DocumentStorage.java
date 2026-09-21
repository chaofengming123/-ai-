package com.example.aiknowledge.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.aiknowledge.model.StoredFile;

@Component
public class DocumentStorage {
    private final LocalDocumentStorage local;
    private final Optional<MinioDocumentStorage> minio;
    private final String backend;
    public DocumentStorage(LocalDocumentStorage local, Optional<MinioDocumentStorage> minio,
                           @Value("${app.documents.backend}") String backend) {
        if (!backend.equals("local") && !backend.equals("minio")) throw new IllegalArgumentException("Unknown document storage backend");
        this.local=local; this.minio=minio; this.backend=backend;
    }
    public StoredFile save(byte[] bytes) {
        if (backend.equals("local")) return new StoredFile("LOCAL",null,local.save(bytes));
        return saveToMinio(bytes);
    }
    public StoredFile saveToMinio(byte[] bytes) {
        var target = minio.orElseThrow(() -> new IllegalStateException("MinIO storage is disabled"));
        String key=UUID.randomUUID().toString();
        target.save(key,bytes);
        return new StoredFile("MINIO",target.bucket(),key);
    }
    public byte[] read(StoredFile file) {
        return switch (file.backend()) {
            case "LOCAL" -> local.read(file.key());
            case "MINIO" -> minio.orElseThrow().read(file.bucket(),file.key());
            default -> throw new IllegalArgumentException("Unknown document storage backend");
        };
    }
    public void remove(StoredFile file) {
        switch (file.backend()) {
            case "LOCAL" -> local.remove(file.key());
            case "MINIO" -> minio.orElseThrow().remove(file.bucket(),file.key());
            default -> throw new IllegalArgumentException("Unknown document storage backend");
        }
    }
}
