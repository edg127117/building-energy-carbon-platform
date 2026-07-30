package com.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tdengine")
public class TdengineProperties {
    private String url;
    private String username;
    private String password;
    private String database = "iot_telemetry";
    private int keep = 3650;
    private String duration = "10d";

    /** 空调原始测点数据超级表（设计书 §2.1） */
    private String stRawMinute = "st_raw_minute";

    /** HVAC 逐条真实上报超级表，用于复审、去重和分钟重建 */
    private String stRawEvent = "st_raw_event";

    /** 性能指标计算结果超级表（设计书 §2.2） */
    private String stIndicatorMinute = "st_indicator_minute";

    /** 公式计算失败审计超级表。 */
    private String stFormulaCalcException = "st_formula_calc_exception";
}
