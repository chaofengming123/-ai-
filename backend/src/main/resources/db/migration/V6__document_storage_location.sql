-- 第十八课的记录仍指向本地文件；不能把默认值改成 MINIO 后假装文件已迁移。
ALTER TABLE document
    ADD COLUMN storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN storage_bucket VARCHAR(63) NULL,
    ADD CONSTRAINT chk_document_storage CHECK (
        (storage_backend='LOCAL' AND storage_bucket IS NULL) OR
        (storage_backend='MINIO' AND storage_bucket IS NOT NULL)
    );
