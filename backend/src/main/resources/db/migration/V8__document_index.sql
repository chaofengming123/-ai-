CREATE TABLE document_index (
    document_id BIGINT NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL DEFAULT 'NOT_INDEXED',
    attempt_id VARCHAR(36) NULL,
    error_message VARCHAR(300) NULL,
    active_collection VARCHAR(120) NULL,
    active_model VARCHAR(200) NULL,
    active_space VARCHAR(64) NULL,
    source_sha256 VARCHAR(64) NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    dimensions INT NOT NULL DEFAULT 0,
    source_characters INT NOT NULL DEFAULT 0,
    note VARCHAR(500) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    indexed_at TIMESTAMP NULL,
    CONSTRAINT fk_document_index_document FOREIGN KEY(document_id) REFERENCES document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO app_permission(code,label) VALUES ('document:index','建立文档索引');
INSERT INTO app_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code IN ('EDITOR','ADMIN') AND p.code='document:index';
