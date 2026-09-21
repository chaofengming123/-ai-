ALTER TABLE app_user
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN'));
