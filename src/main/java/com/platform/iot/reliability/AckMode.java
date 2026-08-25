package com.platform.iot.reliability;

/** 平台可提供的应用 ACK 能力等级，枚举顺序不作为比较依据。 */
public enum AckMode {
    DEVICE_DIRECT,
    ADAPTER_PROXY,
    EVIDENCE_ONLY
}
