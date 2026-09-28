package com.platform.weather;

import com.platform.audit.*;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 复用公共审计仓储，记录配置变更和后台执行责任账号，不保存载荷或凭据。 */
@Component
public class WeatherAudit {
    private final AuditEvidenceWriter writer;
    private final AuditGovernanceProperties properties;
    public WeatherAudit(AuditEvidenceWriter writer,AuditGovernanceProperties properties) { this.writer=writer;this.properties=properties; }
    public void record(long actor,String building,String action,String id,String state,String reason) {
        // 任务状态与公共审计结果使用不同枚举；重试表示本次尝试失败，原状态保留在摘要和任务表。
        String result = switch (state) {
            case "SUCCEEDED" -> "SUCCESS";
            case "FAILED", "RETRY_WAIT" -> "FAILED";
            case "BLOCKED", "CANCELLED" -> "REJECTED";
            default -> throw new IllegalArgumentException("Unsupported weather audit state: " + state);
        };
        writer.append(new AuditEvidence("WEATHER",building,"USER",actor,action,"WEATHER_BACKEND",id,null,null,null,"state="+state,result,reason,id,LocalDateTime.now(),properties.getEnvironmentMode(),false));
    }
}
