-- 旧 JWT 没有安全版本，升级后必须重新登录。版本与账号安全变更在同一事务递增。
ALTER TABLE sys_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 1;
