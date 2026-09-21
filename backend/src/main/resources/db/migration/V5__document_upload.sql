CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    object_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    file_type VARCHAR(16) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT chk_document_size CHECK (file_size > 0 AND file_size <= 1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO app_permission(code,label) VALUES ('document:read','查看文档'),('document:upload','上传文档');
INSERT INTO app_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p
WHERE (p.code='document:read' AND r.code IN ('USER','EDITOR','ADMIN'))
   OR (p.code='document:upload' AND r.code IN ('EDITOR','ADMIN'));
