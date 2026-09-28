package com.platform.weather;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** 默认关闭外部采集和日量计算；启用前必须配置真实建筑位置和责任账号。 */
@Data
@Component
@ConfigurationProperties(prefix="weather")
public class WeatherProperties {
    private boolean enabled;
    private boolean openMeteoEnabled;
    private boolean chinaWeatherEnabled;
    private boolean energyEnabled;
    private long taskActorId;
    private List<EnergyTarget> energyTargets = new ArrayList<>();
    @Data
    public static class EnergyTarget {
        private String buildingId;
        private String systemId;
        private String pointId;
        private long actorId;
    }
}
