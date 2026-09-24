package com.platform.iot.daikin.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** 凭据只从受保护的服务端配置注入；默认关闭，不向浏览器或来源登记接口返回。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "daikin.device-client")
public class DaikinDeviceClientProperties {
    private boolean enabled;
    private String sourceId;
    private URI baseUri;
    private String appId;
    private String password;
    private String communicationKey;
    private String signatureSalt;
    /** 本项目厂家界面以°C显示室温与设定温度；现场字段对照有异议时可关闭正式温度解码。 */
    private boolean temperatureCelsiusConfirmed = true;
}
