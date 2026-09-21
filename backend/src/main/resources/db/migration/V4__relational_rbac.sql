CREATE TABLE app_role (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 label VARCHAR(60) NOT NULL
);
CREATE TABLE app_permission (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 label VARCHAR(60) NOT NULL
);
CREATE TABLE app_user_role (
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 PRIMARY KEY (user_id, role_id),
 FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 FOREIGN KEY (role_id) REFERENCES app_role(id)
);
CREATE TABLE app_role_permission (
 role_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 PRIMARY KEY (role_id, permission_id),
 FOREIGN KEY (role_id) REFERENCES app_role(id) ON DELETE CASCADE,
 FOREIGN KEY (permission_id) REFERENCES app_permission(id)
);
INSERT INTO app_role(code,label) VALUES ('USER','普通用户'),('EDITOR','编辑者'),('ADMIN','管理员');
INSERT INTO app_permission(code,label) VALUES
 ('knowledge-base:read','查看知识库'),('knowledge-base:create','新建知识库'),
 ('knowledge-base:update','编辑知识库'),('knowledge-base:delete','删除知识库');
INSERT INTO app_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p
 WHERE r.code='ADMIN' OR p.code='knowledge-base:read'
 OR (r.code='EDITOR' AND p.code IN ('knowledge-base:create','knowledge-base:update'));
-- 先保留旧账号角色关系，再删除旧字段。不要修改已应用的 V3。
INSERT INTO app_user_role(user_id,role_id)
 SELECT u.id,r.id FROM app_user u JOIN app_role r ON r.code=UPPER(u.role);
ALTER TABLE app_user DROP CHECK chk_app_user_role, DROP COLUMN role;
