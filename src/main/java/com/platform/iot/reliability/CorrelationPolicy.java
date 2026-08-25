package com.platform.iot.reliability;

/** 受信任设备绑定允许采用的消息关联键。 */
public enum CorrelationPolicy {
    SOURCE_MESSAGE_ID,
    BOOT_ID_AND_SEQ,
    SEQ_AND_COLLECTED_AT,
    UNIQUE_COLLECTED_AT,
    NONE
}
