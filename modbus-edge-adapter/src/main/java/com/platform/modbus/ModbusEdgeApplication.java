package com.platform.modbus;

import com.platform.modbus.config.ModbusEdgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启动独立的只读 Modbus 边缘采集进程，不承载云端平台业务。 */
@EnableScheduling
@EnableConfigurationProperties(ModbusEdgeProperties.class)
@SpringBootApplication
public class ModbusEdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModbusEdgeApplication.class, args);
    }
}
