package com.platform.iot.daikin.control;

import java.util.List;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;

/** 批次只包含一个共同动作；全部时间和耗时为毫秒，回显结论不代表现场操作者来源。 */
public final class DaikinBatchContracts {
    private DaikinBatchContracts() { }
    public enum ItemStatus { QUEUED, DISPATCHING, ACTIVE, MATCHED, ALREADY_TARGET, UNSUPPORTED, FAILED, UNCONFIRMED, CANCELLED, NOT_SENT, SUPERSEDED }
    public record PreviewRequest(List<String> equipmentIds) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("预检含未知字段"); }
    }
    public record PreviewItem(String equipmentId, String revision, String reasonCode, List<ActionOption> actions,
                              List<String> impactedEquipmentIds, boolean impactKnown, java.util.Map<String,String> impactedEquipmentLabels) { }
    public record Preview(String previewId, long expiresAt, List<PreviewItem> items) { }
    public record SubmitBatch(String previewId, Action action, String value, String idempotencyKey, boolean confirmLinkedImpact) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("批次含未知字段"); }
    }
    public record Item(String itemId, String equipmentId, ItemStatus status, String reasonCode, String operationId,
                       String observedValue, String unitStatus, long createdAt, Long dispatchedAt, Long acceptedAt,
                       Long readbackAt, Long queuedMillis, Long acceptanceMillis, Long readbackMillis) { }
    public record Batch(String batchId, Action action, String value, long createdAt, long updatedAt, boolean completed,
                        int total, int completedCount, int queuedCount, int activeCount, int attentionCount, List<Item> items) { }
    public record PreparedItem(PreviewItem view, DaikinControlAccess.Target target, String relationshipHash,
                               List<DaikinControlAccess.Target> impactedTargets) { }
    public record SavedPreview(String previewId, long userId, long expiresAt, List<PreparedItem> items) { }
}
