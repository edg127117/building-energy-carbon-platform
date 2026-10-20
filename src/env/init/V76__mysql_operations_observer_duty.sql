-- 只登记运维观测职责；现有业务角色和管理员均不自动获得运行指标访问权限。
INSERT INTO sys_backend_duty (duty_key,duty_name,description,status,risk_level,version)
VALUES ('OPERATIONS_OBSERVER','运行指标观测','经独立管理端口读取运行指标，不包含线程或堆转储','ENABLED','HIGH',1);
