package com.example.aiknowledge.service;

import java.io.ByteArrayInputStream;
import java.util.Map;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.example.aiknowledge.exception.DocumentException;

@Component
@ConditionalOnProperty(name="app.documents.backend", havingValue="minio")
public class MinioDocumentStorage {
    private final MinioClient client;
    private final String bucket;
    public MinioDocumentStorage(@Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.bucket}") String bucket) {
        client = MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).build();
        client.setTimeout(5000,15000,15000);
        this.bucket = bucket;
    }
    public String bucket() { return bucket; }
    public void save(String key, byte[] bytes) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
                catch (ErrorResponseException error) {
                    if (!"BucketAlreadyOwnedByYou".equals(error.errorResponse().code())) throw error;
                }
            }
            // 不设置公开策略；生成的 key 与原文件名无关。
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(bytes),bytes.length,-1)
                    .contentType("application/octet-stream")
                    .headers(Map.of("If-None-Match", "*"))
                    .build());
        } catch (Exception error) {
            // 请求失败也可能已经写入。保留不确定对象，避免误删；后续可对账清理。
            logFailure("upload",error);
            throw unavailable();
        }
    }
    public byte[] read(String storedBucket, String key) {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(storedBucket).object(key).build())) {
            byte[] bytes = input.readNBytes(DocumentService.MAX_BYTES + 1);
            if (bytes.length > DocumentService.MAX_BYTES) throw unavailable();
            return bytes;
        } catch (Exception error) { logFailure("download",error); throw unavailable(); }
    }
    public void remove(String storedBucket, String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(storedBucket).object(key).build()); }
        catch (Exception error) {
            // 日志不包含连接凭据或带签名的请求。
            LoggerFactory.getLogger(MinioDocumentStorage.class).error("Object cleanup failed: bucket={}, key={}",storedBucket,key);
        }
    }
    private DocumentException unavailable() {
        return new DocumentException(DocumentException.Kind.STORAGE_FAILURE,
                "对象存储暂不可用，请检查 MinIO 服务及后端连接配置。");
    }
    private void logFailure(String operation,Exception error) {
        String reason=error instanceof ErrorResponseException response ? response.errorResponse().code() : error.getClass().getSimpleName();
        LoggerFactory.getLogger(MinioDocumentStorage.class).warn("MinIO {} failed: {}",operation,reason);
    }
}
