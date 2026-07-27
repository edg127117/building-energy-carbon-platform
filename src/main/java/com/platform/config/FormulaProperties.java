package com.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "formula")
public class FormulaProperties {

    private boolean enabled = true;
    private double atmosphericPressureKpa = 101.325;
    private long indicatorConfigRefreshMs = 60_000L;
    private boolean recoveryEnabled = true;
    private int recoveryMinutes = 10;
    private long recoveryDelayMs = 600_000L;
}
