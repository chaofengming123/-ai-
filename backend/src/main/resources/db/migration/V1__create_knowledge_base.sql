CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    description VARCHAR(300) NOT NULL,
    document_count INT NOT NULL DEFAULT 0,
    category VARCHAR(60) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_base_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO knowledge_base (id, name, description, document_count, category) VALUES
(1, '公司制度知识库', '员工手册、考勤制度与差旅报销政策。', 3, '人力资源'),
(2, '工程技术知识库', '开发规范、系统架构与技术协作文档。', 10, '工程技术');
