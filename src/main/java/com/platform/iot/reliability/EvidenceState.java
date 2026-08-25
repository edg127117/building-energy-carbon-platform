package com.platform.iot.reliability;

/** 某一 ACK 事实是否有当前系统可验证的证据。 */
public enum EvidenceState {
    OBSERVED,
    PENDING,
    FAILED,
    UNKNOWN,
    NOT_APPLICABLE
}
