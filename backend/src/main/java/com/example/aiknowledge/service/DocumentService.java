package com.example.aiknowledge.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.model.DocumentInfo;
import com.example.aiknowledge.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import static com.example.aiknowledge.exception.DocumentException.Kind.*;

@Service
public class DocumentService {
    public static final int MAX_BYTES = 1024 * 1024;
    private final DocumentMapper documents;
    private final KnowledgeBaseMapper bases;
    private final LocalDocumentStorage storage;
    public DocumentService(DocumentMapper documents, KnowledgeBaseMapper bases, LocalDocumentStorage storage) {
        this.documents = documents; this.bases = bases; this.storage = storage;
    }
    public List<DocumentInfo> list(long baseId) {
        if (bases.selectById(baseId) == null)
            throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND, "知识库不存在。");
        return documents.list(baseId);
    }
    @Transactional
    public DocumentInfo upload(long baseId, MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw new DocumentException(INVALID_INPUT, "文件名需为 1–180 个字符，不能包含路径或控制字符。");
        String type = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!name.contains(".") || !Set.of("txt", "md").contains(type))
            throw new DocumentException(INVALID_INPUT, "本课只支持 UTF-8 编码的 .txt 或 .md 文件。");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException error) {
            throw new DocumentException(INVALID_INPUT, "无法读取上传文件，请重新选择文件。");
        }
        if (bytes.length == 0) throw new DocumentException(INVALID_INPUT, "不能上传空文件。");
        if (bytes.length > MAX_BYTES) throw new DocumentException(TOO_LARGE, "文件不能超过 1 MB。");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw new CharacterCodingException();
        } catch (CharacterCodingException error) {
            throw new DocumentException(INVALID_INPUT, "文件必须是 UTF-8 文本，不能是改后缀的二进制文件。");
        }
        // 锁住知识库，使上传与删除不会同时跨过存在性检查。
        if (bases.findForUpdate(baseId) == null)
            throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND, "知识库不存在。");
        String key = storage.save(bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.remove(key);
            }
        });
        documents.insert(baseId, name, key, type, bytes.length);
        return documents.findByKey(key);
    }
}
