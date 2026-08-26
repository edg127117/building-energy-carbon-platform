package com.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
/** 在安全过滤链和异常映射边界记录脱敏拒绝证据；写入失败只记录服务日志，不能泄露给客户端。 */
public class SecurityAuditService {
    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final AuditEvidenceWriter writer;
    private final AuditGovernanceProperties properties;

    public void recordDenied(HttpServletRequest request, Long operatorId, String reasonCode) {
        record(request, operatorId, "HTTP_ACCESS_DENIED", "HTTP_ENDPOINT", request.getRequestURI(),
                "DENIED", reasonCode);
    }

    /** 登录、登录失败和 Token 撤销只记录稳定账号 ID；失败时不保存攻击者提供的用户名。 */
    public void recordAuthentication(HttpServletRequest request, Long operatorId, String actionType,
                                     String result, String reasonCode) {
        record(request, operatorId, actionType, "SECURITY_IDENTITY",
                operatorId == null ? "ANONYMOUS" : Long.toString(operatorId), result, reasonCode);
    }

    private void record(HttpServletRequest request, Long operatorId, String actionType,
                        String objectType, String rawObjectId, String result, String reasonCode) {
        try {
            String objectId = rawObjectId.length() <= 128 ? rawObjectId : rawObjectId.substring(0, 128);
            writer.append(new AuditEvidence("SYSTEM_SECURITY", null, "USER", operatorId,
                    actionType, objectType, objectId, null, null,
                    null, null, result, reasonCode, TraceContext.from(request), LocalDateTime.now(),
                    properties.getEnvironmentMode(), false));
        } catch (RuntimeException failure) {
            log.error("安全审计写入失败: traceId={}, actionType={}, reasonCode={}",
                    TraceContext.from(request), actionType, reasonCode, failure);
        }
    }
}
