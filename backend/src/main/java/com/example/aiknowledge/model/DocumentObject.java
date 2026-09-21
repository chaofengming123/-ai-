package com.example.aiknowledge.model;

public record DocumentObject(long id, String storageBackend, String storageBucket,
                             String objectKey, long fileSize) {
    public StoredFile location() { return new StoredFile(storageBackend, storageBucket, objectKey); }
}
