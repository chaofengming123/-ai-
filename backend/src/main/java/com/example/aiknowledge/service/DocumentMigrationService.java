package com.example.aiknowledge.service;

import java.util.Arrays;
import com.example.aiknowledge.mapper.DocumentMapper;
import com.example.aiknowledge.exception.DocumentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class DocumentMigrationService {
    private final DocumentMapper documents;
    private final DocumentStorage storage;
    public DocumentMigrationService(DocumentMapper documents,DocumentStorage storage) {
        this.documents=documents; this.storage=storage;
    }
    @Transactional
    public boolean migrate(long id, boolean apply) {
        var old=documents.lockObject(id);
        if (old==null || !old.storageBackend().equals("LOCAL")) return false;
        byte[] bytes=storage.read(old.location());
        if (bytes.length!=old.fileSize()) throw new DocumentException(DocumentException.Kind.STORAGE_FAILURE,
                "旧文件大小与记录不一致，已保留原记录。");
        if (!apply) return true;
        var target=storage.saveToMinio(bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status==STATUS_ROLLED_BACK) storage.remove(target);
            }
        });
        // 本课最大 5 MB，逐字节比较即可验证完整性，确认后才更新数据库定位信息。
        if (!Arrays.equals(bytes,storage.read(target))) throw new DocumentException(DocumentException.Kind.STORAGE_FAILURE,
                "迁移后文件校验失败，已保留原记录。");
        if (documents.migrate(id,target.key(),target.bucket())!=1) throw new IllegalStateException("Migration conflict");
        // 不删除旧本地副本；失败可以重试，已成功迁移的记录会跳过。
        return true;
    }
}
