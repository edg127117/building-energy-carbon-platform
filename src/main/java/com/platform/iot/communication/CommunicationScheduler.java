package com.platform.iot.communication;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界游标扫描只更新 MySQL 通讯状态，单目标失败不会跳过其他设备或访问真实采集资源。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommunicationScheduler {
    private final DeviceCommunicationService service;
    private final CommunicationProperties properties;
    private String cursor="";
    @Scheduled(fixedDelayString="${device-communication.scan-delay-ms:1000}")
    public synchronized void scan() {
        if(!properties.isScanEnabled())return;
        var identities=service.identitiesAfter(cursor,properties.getBatchSize());
        if(identities.isEmpty()){cursor="";return;}
        for(String id:identities) {
            try { service.reconcile(id); }
            catch(RuntimeException failure){log.warn("通讯状态扫描失败 identityId={} cause={}",id,failure.getClass().getSimpleName());}
        }
        cursor=identities.getLast();
    }
}
