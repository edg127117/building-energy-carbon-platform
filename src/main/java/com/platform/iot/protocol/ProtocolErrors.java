package com.platform.iot.protocol;

import com.platform.framework.exception.BusinessException;

/** 新协议配置接口的稳定错误，不泄露数据库或原始报文内容。 */
public final class ProtocolErrors {
    private ProtocolErrors() {}
    public static BusinessException invalid(String message) {
        return new BusinessException(400, "PROTOCOL_VALIDATION_FAILED", message);
    }
    public static BusinessException conflict() {
        return new BusinessException(409, "PROTOCOL_REVISION_CONFLICT", "草稿已被更新，请重新读取后编辑");
    }
    public static BusinessException notFound() {
        return new BusinessException(404, "PROTOCOL_NOT_FOUND", "协议草稿不存在");
    }
}
