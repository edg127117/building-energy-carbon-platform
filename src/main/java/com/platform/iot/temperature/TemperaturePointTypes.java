package com.platform.iot.temperature;

/**
 * 治理档案的 ANALOG 与既有温度点的 AI 都表示模拟量；只兼容类型，不改写档案。
 * 单位、非计算用途、启用状态和归属仍由接入及查询边界分别校验。
 */
public final class TemperaturePointTypes {
    private TemperaturePointTypes() { }

    public static boolean isAnalog(String dataType) {
        return "ANALOG".equals(dataType) || "AI".equals(dataType);
    }
}
