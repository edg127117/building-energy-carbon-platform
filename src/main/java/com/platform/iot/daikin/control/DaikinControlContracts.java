package com.platform.iot.daikin.control;

import java.util.List;

/** 单设备控制和批量许可的公开契约；批量许可不下发命令，云端核验不表示物理执行确认。 */
public final class DaikinControlContracts {
    private DaikinControlContracts() { }

    public enum Action {
        ON_OFF("onOff", "onOff"), TEMPERATURE("temperature", "temperature"),
        MODE("mode", "mode"), AIR_FLOW("airFlow", "airflowDirection"), FAN_SPEED("fanSpeed", "fanSpeed");
        public final String parameter;
        public final String observedField;
        Action(String parameter, String observedField) { this.parameter = parameter; this.observedField = observedField; }
    }

    public enum Status { PREPARING, SENDING, ACCEPTED, OBSERVED_MATCH, OBSERVED_STABLE, CLOUD_VERIFIED, CONFIRMED, REJECTED, NOT_SENT, UNKNOWN, UNCONFIRMED, CLOSED }
    public record Submit(Action action, String value, String idempotencyKey, String revision) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("控制请求含未知字段"); }
    }
    public record Close(String reason) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("结束请求含未知字段"); }
    }
    public record ActionOption(Action action, boolean allowed, String reasonCode, List<String> values,
                               Integer minimum, Integer maximum) { }
    public record Options(String equipmentId, boolean canControl, String reasonCode, String revision,
                          List<ActionOption> actions, String activeOperationId, Integer currentTemperature, boolean testOpenControls) {
        public Options(String equipmentId, boolean canControl, String reasonCode, String revision,
                       List<ActionOption> actions, String activeOperationId, Integer currentTemperature) {
            this(equipmentId, canControl, reasonCode, revision, actions, activeOperationId, currentTemperature, false);
        }
    }
    public record GrantAccess(boolean canManage, boolean testOpenControls) {
        public GrantAccess(boolean canManage) { this(canManage, false); }
    }
    public record BuildingOption(String buildingId, String buildingName) { }
    public record BatchGrant(List<String> equipmentIds, List<Action> actions, String evidence) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("开放请求含未知字段"); }
    }
    public record GrantItem(String equipmentId, boolean opened, String reasonCode, Long version, List<ActionOption> actions) { }
    public record BatchGrantResult(List<GrantItem> items) { }
    public record RecoveryCandidate(Operation operation, boolean eligible, String reasonCode, boolean simpleRecovery) {
        public RecoveryCandidate(Operation operation, boolean eligible, String reasonCode) { this(operation, eligible, reasonCode, false); }
    }
    public record RecoveryBaseline(String onOff, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = RecoveryTemperatureDeserializer.class) Integer temperature) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("恢复基线含未知字段"); }
    }
    public record RecoveryProgress(String operationId, String onOff, Integer temperature, String mode,
                                   String fanSpeed, String airFlow, String unitStatus, int stableCount,
                                   Long stableSince, Long lastReadAt, boolean canRecover, String proof, String reasonCode) { }
    public record RecoverCurrentBaseline(String onOff, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = RecoveryTemperatureDeserializer.class) Integer temperature,
                                         String proof, String reason, boolean onsiteConfirmed) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("恢复请求含未知字段"); }
    }
    public static class RecoveryTemperatureDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"恢复设温必须为整数");
            return parser.getIntValue();
        }
    }
    public record Operation(String operationId, String equipmentId, Action action, String value, Status status,
                            String reasonCode, String vendorCode, String observedValue, long createdAt,
                            long updatedAt, String closedReason, Long deadlineAt, int stableCount, Long stableSince,
                            Long lastReadAt, String unitStatus, boolean isolated, boolean cloudStable, String verificationPolicy) { }
}
