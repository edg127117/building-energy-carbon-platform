package com.platform.iot.temperature;

import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import java.util.Map;

/** 厂家边界：可信身份、字段语义和来源协议由适配器提供，模板选择不得猜测协议。 */
public interface TemperatureAdapter {
    String id();
    boolean supports(BizPendingDevice pending);
    Context context(BizPendingDevice pending);
    Map<String, String> fields();
    String sourceSystem();
    String transportType();
    default String unit() { return "°C"; }
    default String alias(BizPendingDevice pending, String field) {
        return pending.getIdentityType() + ":" + pending.getIdentityValue() + ":" + field;
    }
    record Context(String buildingId, String sourceScope, String model, String revision) { }
}
