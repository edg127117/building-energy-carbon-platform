package com.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TDengine 连接、数据库生命周期和 HVAC 超级表名称配置。
 *
 * <p>{@link TdengineConfig} 用连接字段装配专用数据源并创建 Schema，各时序 Repository
 * 只读取数据库名和对应超级表名。表名会在进入 SQL 前执行标识符白名单校验，不能把
 * 外部业务值写入这些配置项。</p>
 */
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

    /** 追加式公式尝试和质量策略判定事实。 */
    private String stFormulaCalcAttemptV2 = "st_formula_calc_attempt_v2";

    /** 指标分钟当前有效状态投影。 */
    private String stIndicatorMinuteState = "st_indicator_minute_state";
}
