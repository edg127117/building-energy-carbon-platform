package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.*;

/** 独立单通道刷新已登记查询；数据库租约支持重启恢复，不占用定时调度线程或并发压垮核算库。 */
@Component
class CarbonDisplayQueryWorker implements AutoCloseable {
    private final CarbonDisplayQueryRepository repository;
    private final CarbonDisplaySourceVersion versions;
    private final CarbonDisplayQueryService service;
    private final CarbonDisplayQueryProperties properties;
    private final CarbonDisplayTaskIdentity identity;
    private final CarbonDisplayPrewarmService prewarm;
    private long discoverAfter;
    private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("carbon-display-refresh-",0).factory());
    CarbonDisplayQueryWorker(CarbonDisplayQueryRepository repository,CarbonDisplaySourceVersion versions,CarbonDisplayQueryService service,
            CarbonDisplayQueryProperties properties,CarbonDisplayTaskIdentity identity,CarbonDisplayPrewarmService prewarm){
        this.repository=repository;this.versions=versions;this.service=service;this.properties=properties;this.identity=identity;this.prewarm=prewarm;
    }
    @Scheduled(fixedDelayString="${carbon-display-query.scan-delay-ms:1000}",initialDelayString="${carbon-display-query.initial-delay-ms:15000}")
    public void scan(){
        if(!properties.isEnabled()||!active.compareAndSet(false,true))return;
        try{worker.submit(()->{try{refresh();}finally{active.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){active.set(false);}
    }
    void refresh(){
        long now=System.currentTimeMillis();
        try {
            if(now>=discoverAfter){
                discoverAfter=now+properties.getRecheckInterval().toMillis();
                try{prewarm.registerDefaults();}catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Carbon display default registration unavailable");}
            }
            for(var h:repository.due(now)) {
                String token=null;
                try {
                    var q=service.parse(h);String current=versions.version(q);
                    if("READY".equals(h.state())&&current.equals(h.built())){repository.checked(h,now);continue;}
                    if(!current.equals(h.source())){repository.request(q,h.query(),h.user(),current,now);h=repository.head(h.key());}
                    token=repository.claim(h,now);if(token==null)continue;
                    // 任务不存角色或Token；执行时重读账号/角色，再由原服务检查当前建筑权限。
                    var currentRoles=identity.currentRoles(h.user());
                    service.rebuild(h,token,currentRoles);
                }catch(RuntimeException e){
                    String code=e instanceof BusinessException b&&b.getErrorCode()!=null?b.getErrorCode():"CARBON_DISPLAY_REFRESH_FAILED";
                    int status=e instanceof BusinessException b?b.getCode():503;
                    if(token!=null)repository.failed(h,token,code,status,System.currentTimeMillis());else repository.defer(h,System.currentTimeMillis());
                    LoggerFactory.getLogger(getClass()).warn("Carbon display refresh failed: kind={}, code={}",h.kind(),code);
                    continue;
                }
                return;
            }
        }catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Carbon display refresh scan unavailable",e);}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){worker.shutdownNow();}
}
