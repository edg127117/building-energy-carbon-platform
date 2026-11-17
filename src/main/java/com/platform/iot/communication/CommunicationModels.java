package com.platform.iot.communication;

import jakarta.validation.constraints.*;
import java.util.List;

/** 通讯可用性独立于运行、测点质量和数据落盘；时间均为平台 UTC 毫秒。 */
public final class CommunicationModels {
    private CommunicationModels() { }
    public record View(boolean applicable, String value, String reason, String source, Long observedAt,
                       String scope, boolean delayed, boolean alarmOpen, Long nextDeadline, long policyRevision) {
        public static View unmonitored() { return new View(false, "UNKNOWN", "NOT_MONITORED", null, null, "DEVICE", false, false, null, 0); }
    }
    public record Policy(String scopeKey, int periodSeconds, boolean monitored, String evidence, long revision) { }
    public record PolicyRequest(@Min(5) @Max(86400) int periodSeconds, @NotNull Boolean monitored,
                                @NotBlank @Size(max=500) String evidence, @Min(0) long expectedRevision) { }
    public record GatewayRequest(@Size(max=200) String gatewayKey, @NotBlank @Size(max=500) String evidence,
                                 @Min(0) long expectedRevision) { }
    public record Signal(String identityId, String equipmentId, String buildingId, String source,
                         String evidenceKey, long observedAt, long receivedAt, boolean offline, String reason,Integer mappingRevision,String profileCode) {
        public Signal(String identityId,String equipmentId,String buildingId,String source,String evidenceKey,long observedAt,long receivedAt,boolean offline,String reason) {
            this(identityId,equipmentId,buildingId,source,evidenceKey,observedAt,receivedAt,offline,reason,null,null);
        }
        public Signal(String identityId,String equipmentId,String buildingId,String source,String evidenceKey,long observedAt,long receivedAt,boolean offline,String reason,Integer mappingRevision) {
            this(identityId,equipmentId,buildingId,source,evidenceKey,observedAt,receivedAt,offline,reason,mappingRevision,null);
        }
    }
    public record Transition(String eventId, String identityId, String buildingId, String beforeState,
                             String afterState, String reason, long observedAt, long policyRevision) { }
    public record EquipmentScope(String equipmentId, String buildingId) { }
    public record Summary(long online, long offline, long unknown, long notMonitored, long delayed) { }
}
