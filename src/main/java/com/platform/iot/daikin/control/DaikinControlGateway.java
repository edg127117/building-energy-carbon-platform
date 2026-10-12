package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.JsonNode;
import static com.platform.iot.daikin.control.DaikinControlContracts.Action;

/** 厂家边界只允许单次下发；测试直接注入内存替身，生产网关须双重显式启用。 */
public interface DaikinControlGateway {
    boolean supports(String sourceId);
    Snapshot read(DaikinControlAccess.Target target);
    Reply send(DaikinControlAccess.Target target, Action action, String value);

    record Snapshot(JsonNode controller, JsonNode unit) { }
    record Reply(String code) { }
}
