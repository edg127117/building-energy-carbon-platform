package com.platform.iot.daikin.control;

/** 设备开放使用独立只读能力；控制关闭时仍可核验，接口不提供发送方法。 */
public interface DaikinControlGrantReader {
    boolean supports(String sourceId);
    DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target);
}
