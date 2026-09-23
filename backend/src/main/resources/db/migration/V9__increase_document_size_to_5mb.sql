-- 保留旧迁移的校验和，通过新迁移扩展已有表的约束。
ALTER TABLE document DROP CHECK chk_document_size;
ALTER TABLE document ADD CONSTRAINT chk_document_size
    CHECK (file_size > 0 AND file_size <= 5242880);
