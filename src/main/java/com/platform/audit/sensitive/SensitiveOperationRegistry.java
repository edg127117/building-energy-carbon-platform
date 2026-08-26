package com.platform.audit.sensitive;

import com.platform.audit.AuditGovernanceErrors;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
/** 启动时冻结操作码到处理器的唯一映射，禁止运行时注册任意命令。 */
public class SensitiveOperationRegistry {
    private final Map<String, SensitiveOperationHandler> handlers;

    public SensitiveOperationRegistry(Collection<SensitiveOperationHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                SensitiveOperationHandler::operationCode, Function.identity()));
    }

    public SensitiveOperationHandler require(String operationCode) {
        SensitiveOperationHandler handler = handlers.get(operationCode);
        if (handler == null) {
            throw new BusinessException(400, AuditGovernanceErrors.OPERATION_FORBIDDEN, "不支持的敏感操作");
        }
        return handler;
    }
}
