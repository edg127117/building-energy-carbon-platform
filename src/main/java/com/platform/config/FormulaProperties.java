package com.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公式引擎、气象换算和指标补算的集中配置。
 *
 * <p>生产环境默认开启分钟计算和低频补算；测试环境可关闭这些开关，从而
 * 不注册公式事件处理、查询 API 和补算任务。缓存等基础 Bean 仍可保留，
 * 但不会被关闭后的公式业务链路调用。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "formula")
public class FormulaProperties {

    /** 是否启用公式引擎及其查询、缓存和恢复组件。 */
    private boolean enabled = true;

    /** 干球温度和相对湿度换算湿球温度时采用的大气压，单位 kPa。 */
    private double atmosphericPressureKpa = 101.325;

    /** 从 MySQL 刷新活动指标配置快照的间隔，单位毫秒。 */
    private long indicatorConfigRefreshMs = 60_000L;

    /** 是否执行启动检查和低频指标缺口补算。 */
    private boolean recoveryEnabled = true;

    /** 每轮向前检查的已冻结分钟数量。 */
    private int recoveryMinutes = 10;

    /** 两轮指标缺口补算之间的等待时间，单位毫秒。 */
    private long recoveryDelayMs = 600_000L;
}
