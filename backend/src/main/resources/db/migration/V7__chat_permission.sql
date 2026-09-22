INSERT INTO app_permission(code,label) VALUES ('chat:send','使用 AI 对话');
INSERT INTO app_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p
WHERE r.code IN ('USER','EDITOR','ADMIN') AND p.code='chat:send';
