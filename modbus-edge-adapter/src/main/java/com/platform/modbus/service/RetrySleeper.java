package com.platform.modbus.service;

import java.time.Duration;

/** 隔离有限重试的等待动作，测试可替换它且不会引入持久化调度语义。 */
@FunctionalInterface
public interface RetrySleeper {

    void sleep(Duration duration) throws InterruptedException;
}
