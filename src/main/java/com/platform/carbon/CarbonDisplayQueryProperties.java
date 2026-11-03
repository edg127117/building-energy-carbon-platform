package com.platform.carbon;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** 平台技术默认值；不增加建筑配置、专家审核或操作页面。 */
@Component
@ConfigurationProperties(prefix="carbon-display-query")
public class CarbonDisplayQueryProperties {
    private boolean enabled=true;
    private Duration cacheTtl=Duration.ofMinutes(30), recheckInterval=Duration.ofSeconds(30), lease=Duration.ofMinutes(2);
    private int maximumVersionRows=20000;
    private java.util.List<PrewarmTarget> prewarmTargets=java.util.List.of();
    public record PrewarmTarget(String buildingCode,long actorId) { }
    @PostConstruct void validate(){
        if(cacheTtl==null||cacheTtl.isNegative()||cacheTtl.isZero()||recheckInterval==null||recheckInterval.compareTo(Duration.ofSeconds(5))<0
                ||lease==null||lease.compareTo(Duration.ofMinutes(1))<0||maximumVersionRows<100||maximumVersionRows>1000000)
            throw new IllegalStateException("能碳展示读取参数无效");
    }
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public Duration getCacheTtl(){return cacheTtl;} public void setCacheTtl(Duration v){cacheTtl=v;}
    public Duration getRecheckInterval(){return recheckInterval;} public void setRecheckInterval(Duration v){recheckInterval=v;}
    public Duration getLease(){return lease;} public void setLease(Duration v){lease=v;}
    public int getMaximumVersionRows(){return maximumVersionRows;} public void setMaximumVersionRows(int v){maximumVersionRows=v;}
    public java.util.List<PrewarmTarget> getPrewarmTargets(){return prewarmTargets;}
    public void setPrewarmTargets(java.util.List<PrewarmTarget> v){prewarmTargets=v;}
}
