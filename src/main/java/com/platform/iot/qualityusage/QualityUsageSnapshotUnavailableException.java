package com.platform.iot.qualityusage;

import com.platform.framework.exception.BusinessException;

/** 首次快照或请求范围历史策略不可用时，消费入口必须失败关闭。 */
public final class QualityUsageSnapshotUnavailableException extends BusinessException {
    public QualityUsageSnapshotUnavailableException(String message) {
        super(503, QualityUsageErrors.SNAPSHOT_UNAVAILABLE, message);
    }

    public QualityUsageSnapshotUnavailableException(String message, Throwable cause) {
        super(503, QualityUsageErrors.SNAPSHOT_UNAVAILABLE, message);
        initCause(cause);
    }
}
