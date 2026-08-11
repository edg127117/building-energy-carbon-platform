package com.platform.adapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 云服务器上的独立设备报文接入进程，不加载本地平台业务组件。 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class TelemetryAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelemetryAdapterApplication.class, args);
    }
}
