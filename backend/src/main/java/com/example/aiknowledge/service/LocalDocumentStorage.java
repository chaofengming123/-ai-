package com.example.aiknowledge.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.aiknowledge.exception.DocumentException;

@Component
public class LocalDocumentStorage {
    private final Path root;
    public LocalDocumentStorage(@Value("${app.documents.directory}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }
    public String save(byte[] bytes) {
        String key = UUID.randomUUID().toString();
        Path target = root.resolve(key);
        boolean created = false;
        try {
            Files.createDirectories(root);
            // CREATE_NEW 不覆盖同名文件；用户提供的文件名从不参与磁盘路径。
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                created = true;
                output.write(bytes);
            }
            return key;
        } catch (IOException error) {
            if (created) remove(key);
            throw new DocumentException(DocumentException.Kind.STORAGE_FAILURE, "文件保存失败，请检查后端存储目录后重试。");
        }
    }
    public void remove(String key) {
        // 只允许删除本组件生成的 UUID 对象，防止传入任意路径。
        if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException("Invalid object key");
        try { Files.deleteIfExists(root.resolve(key)); }
        catch (IOException error) {
            LoggerFactory.getLogger(LocalDocumentStorage.class).error("Failed to clean up document object {}", key, error);
        }
    }
    public byte[] read(String key) {
        if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException("Invalid object key");
        try (var input = Files.newInputStream(root.resolve(key))) {
            byte[] bytes=input.readNBytes(DocumentService.MAX_BYTES + 1);
            if (bytes.length > DocumentService.MAX_BYTES) throw new IOException("Unexpected file size");
            return bytes;
        } catch (IOException error) {
            throw new DocumentException(DocumentException.Kind.STORAGE_FAILURE,
                    "无法读取旧文档，请确认原文件存在且 DOCUMENT_STORAGE_DIR 指向原上传目录。");
        }
    }
}
