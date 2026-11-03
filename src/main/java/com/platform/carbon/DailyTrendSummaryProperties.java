package com.platform.carbon;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** 展示缓存与可恢复刷新任务的技术参数；不引入逐建筑业务配置。 */
@Component
@ConfigurationProperties(prefix="daily-trend-summary")
public class DailyTrendSummaryProperties {
    private boolean enabled=true;
    private int maximumVersionRows=20000;
    private Duration cacheTtl=Duration.ofMinutes(30);
    private Duration recheckInterval=Duration.ofSeconds(30);
    private Duration lease=Duration.ofMinutes(2);
    private String namespace="bec:daily-trend:v1";
    @PostConstruct void validate() {
        if(maximumVersionRows<100 || maximumVersionRows>1000000 || cacheTtl==null || cacheTtl.isNegative() || cacheTtl.isZero()
                || recheckInterval==null || recheckInterval.compareTo(Duration.ofSeconds(5))<0
                || lease==null || lease.compareTo(Duration.ofMinutes(1))<0 || namespace==null || !namespace.matches("[a-zA-Z0-9:_-]{1,80}"))
            throw new IllegalStateException("日趋势汇总运行参数无效");
    }
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getMaximumVersionRows(){return maximumVersionRows;} public void setMaximumVersionRows(int v){maximumVersionRows=v;}
    public Duration getCacheTtl(){return cacheTtl;} public void setCacheTtl(Duration v){cacheTtl=v;}
    public Duration getRecheckInterval(){return recheckInterval;} public void setRecheckInterval(Duration v){recheckInterval=v;}
    public Duration getLease(){return lease;} public void setLease(Duration v){lease=v;}
    public String getNamespace(){return namespace;} public void setNamespace(String v){namespace=v;}
}
