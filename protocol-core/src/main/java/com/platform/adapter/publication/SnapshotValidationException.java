package com.platform.adapter.publication;

/** 快照违反适配器支持能力或完整集合约束。 */
public class SnapshotValidationException extends IllegalArgumentException {

    private final String errorCode;

    public SnapshotValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
