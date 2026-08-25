package com.platform.iot.reliability;

/** V2 平台终态回执的稳定业务状态。 */
public enum ReceiptStatus {
    PLATFORM_PERSISTED,
    DUPLICATE_PERSISTED,
    MESSAGE_CONFLICT,
    PLATFORM_REJECTED,
    DISCOVERED_NOT_ACTIVE
}
