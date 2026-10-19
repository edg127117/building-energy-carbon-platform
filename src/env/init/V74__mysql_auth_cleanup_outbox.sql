-- 安全状态提交即生效；缓存补偿与版本同事务保存，不保存 Token 或密码。
CREATE TABLE sys_auth_cleanup (
    user_id BIGINT PRIMARY KEY,
    revoked_through_version BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(64),
    INDEX idx_auth_cleanup_due (next_attempt_at)
);
