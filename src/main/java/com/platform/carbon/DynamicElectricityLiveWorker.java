package com.platform.carbon;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单通道合并设备突发输入，数据库租约支持多实例和重启恢复，调度线程只负责唤醒。 */
@Component
@org.springframework.context.annotation.Profile("!test")
public class DynamicElectricityLiveWorker implements AutoCloseable {
    private final DynamicElectricityLiveService service;
    private final AtomicBoolean active=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("dynamic-electricity-",0).factory());
    private long nextMetadataCheck;
    public DynamicElectricityLiveWorker(DynamicElectricityLiveService service){this.service=service;}
    @Scheduled(fixedDelay=1000,initialDelay=5000)
    public void scan() {
        if(!active.compareAndSet(false,true))return;
        try {worker.submit(()->{
            try {
                if(System.currentTimeMillis()>=nextMetadataCheck) {service.refreshMetadata();nextMetadataCheck=System.currentTimeMillis()+30000;}
                service.refresh();
            } catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dynamic calculation scan unavailable",ex);}
            finally {active.set(false);}
        });} catch(RejectedExecutionException ex){active.set(false);}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){worker.shutdownNow();}
}
