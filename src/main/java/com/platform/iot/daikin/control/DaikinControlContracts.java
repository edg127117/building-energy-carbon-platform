package com.platform.iot.daikin.control;

import java.util.List;

/** 单设备控制的公开契约；平台设备编号与厂家身份隔离，回读一致不表示因果确认。 */
public final class DaikinControlContracts {
    private DaikinControlContracts() { }

    public enum Action {
        ON_OFF("onOff", "onOff"), TEMPERATURE("temperature", "temperature"),
        MODE("mode", "mode"), AIR_FLOW("airFlow", "airflowDirection"), FAN_SPEED("fanSpeed", "fanSpeed");
        public final String parameter;
        public final String observedField;
        Action(String parameter, String observedField) { this.parameter = parameter; this.observedField = observedField; }
    }

    public enum Status { PREPARING, SENDING, ACCEPTED, OBSERVED_MATCH, OBSERVED_STABLE, CONFIRMED, REJECTED, NOT_SENT, UNKNOWN, UNCONFIRMED, CLOSED }
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
                          List<ActionOption> actions, String activeOperationId) { }
    public record Operation(String operationId, String equipmentId, Action action, String value, Status status,
                            String reasonCode, String vendorCode, String observedValue, long createdAt,
                            long updatedAt, String closedReason, Long deadlineAt, int stableCount, Long stableSince,
                            Long lastReadAt, String unitStatus, boolean isolated, boolean cloudStable) { }
}
