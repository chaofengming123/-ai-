package com.example.aiknowledge.service;

import java.io.IOException;
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
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final DocumentMapper documents;
    private final KnowledgeBaseMapper bases;
    private final DocumentStorage storage;
    private final java.util.concurrent.Semaphore previews=new java.util.concurrent.Semaphore(2);
    public DocumentService(DocumentMapper documents, KnowledgeBaseMapper bases, DocumentStorage storage) {
        this.documents = documents; this.bases = bases; this.storage = storage;
    }
    public List<DocumentInfo> list(long baseId) {
        if (bases.selectById(baseId) == null)
            throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND, "知识库不存在。");
        return documents.list(baseId);
    }
    public record Download(String name, byte[] bytes) {}
    public record Preview(long documentId,String fileName,String content,boolean truncated,String note) {}
    public record ChunkPreview(long documentId,String fileName,int chunkSize,int overlap,int sourceCharacters,
            boolean sourceTruncated,String note,List<TextChunker.Chunk> chunks) {}
    public ChunkPreview chunks(long id,int size,int overlap) {
        TextChunker.validate(size,overlap);
        var source=preview(id);
        return new ChunkPreview(id,source.fileName(),size,overlap,source.content().length(),source.truncated(),
            source.note(),TextChunker.split(source.content(),size,overlap));
    }
    public Preview preview(long id) {
        if(!previews.tryAcquire()) throw new DocumentException(BUSY,"当前正文预览请求较多，请稍后再试。");
        try {
            var file=download(id);
            var text=DocumentTextExtractor.extract(file.name(),file.bytes());
            return new Preview(id,file.name(),text.content(),text.truncated(),text.note());
        } finally { previews.release(); }
    }
    public Download download(long id) {
        var info=documents.findById(id);
        if (info==null) throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND,"文档不存在。");
        var object=documents.object(id);
        byte[] bytes=storage.read(object.location());
        if (bytes.length!=info.fileSize()) throw new DocumentException(STORAGE_FAILURE,"文档内容与记录不一致，请检查存储。");
        return new Download(info.fileName(),bytes);
    }
    @Transactional
    public DocumentInfo upload(long baseId, MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw new DocumentException(INVALID_INPUT, "文件名需为 1–180 个字符，不能包含路径或控制字符。");
        String type = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!name.contains(".") || !DocumentFormatValidator.TYPES.contains(type))
            throw new DocumentException(INVALID_INPUT, "支持 TXT、Markdown、PDF、DOCX、CSV、TSV、JSON、HTML 和 RTF。");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException error) {
            throw new DocumentException(INVALID_INPUT, "无法读取上传文件，请重新选择文件。");
        }
        if (bytes.length == 0) throw new DocumentException(INVALID_INPUT, "不能上传空文件。");
        if (bytes.length > MAX_BYTES) throw new DocumentException(TOO_LARGE, "文件不能超过 5 MB。");
        DocumentFormatValidator.validate(type,bytes);
        // 锁住知识库，使上传与删除不会同时跨过存在性检查。
        if (bases.findForUpdate(baseId) == null)
            throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND, "知识库不存在。");
        var stored = storage.save(bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.remove(stored);
            }
        });
        documents.insert(baseId, name, stored.key(), type, bytes.length, stored.backend(), stored.bucket());
        return documents.findByKey(stored.key());
    }
}
