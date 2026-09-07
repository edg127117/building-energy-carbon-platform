package com.platform.modbus.config;

/** 表示部署配置无法安全解释，调用方必须拒绝启动对应采集任务。 */
public class ModbusConfigurationException extends IllegalArgumentException {

    public ModbusConfigurationException(String message) {
        super(message);
    }
}
