package com.platform.modbus.service;

import java.time.Duration;

@FunctionalInterface
public interface RetrySleeper {

    void sleep(Duration duration) throws InterruptedException;
}
