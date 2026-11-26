package com.platform.iot.protocol;

import com.platform.framework.exception.BusinessException;

/** 新协议配置接口的稳定错误，不泄露数据库或原始报文内容。 */
public final class ProtocolErrors {
    private ProtocolErrors() {}
    public static BusinessException invalid(String message) {
        return new BusinessException(400, "PROTOCOL_VALIDATION_FAILED", message);
    }
    static ConfigurationError invalid(String path, String message) {
        return new ConfigurationError(path, message);
    }
    static final class ConfigurationError extends BusinessException {
        private final String path;

        ConfigurationError(String path, String message) {
            super(400, "PROTOCOL_VALIDATION_FAILED", message);
            this.path = path;
        }

        String path() { return path; }
    }
    public static BusinessException draftReferenced() {
        return new BusinessException(409, "PROTOCOL_DRAFT_REFERENCED", "该草稿已生成冻结版本，不可删除");
    }
    public static BusinessException conflict() {
        return new BusinessException(409, "PROTOCOL_REVISION_CONFLICT", "草稿已被更新，请重新读取后编辑");
    }
    public static BusinessException notFound() {
        return new BusinessException(404, "PROTOCOL_NOT_FOUND", "协议草稿不存在");
    }
}
