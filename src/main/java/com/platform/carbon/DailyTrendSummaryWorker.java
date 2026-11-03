package com.platform.carbon;

import com.platform.framework.exception.BusinessException;
import com.platform.security.AuthenticationStateService;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.YearMonth;
import java.util.*;

/** 单通道后台刷新，跨实例通过持久化租约合并；恢复扫描不依赖进程内通知且不扩大既有两日读取上限。 */
@Component
public class DailyTrendSummaryWorker implements AutoCloseable {
    private final DailyTrendSummaryRepository repository;
    private final DailyTrendSummaryService service;
    private final DailyTrendSummaryProperties properties;
    private final SysUserMapper users;
    private final SysRoleMapper roles;
    private long discoverAfter;
    private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("daily-summary-refresh-",0).factory());
    public DailyTrendSummaryWorker(DailyTrendSummaryRepository repository,DailyTrendSummaryService service,DailyTrendSummaryProperties properties,SysUserMapper users,SysRoleMapper roles){
        this.repository=repository;this.service=service;this.properties=properties;this.users=users;this.roles=roles;
    }
    @Scheduled(fixedDelayString="${daily-trend-summary.scan-delay-ms:1000}",initialDelayString="${daily-trend-summary.initial-delay-ms:15000}")
    public void scan() {
        if(!properties.isEnabled() || !active.compareAndSet(false,true))return;
        try {worker.submit(()->{try{refresh();}finally{active.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException e){active.set(false);}
    }
    void refresh() {
        long now=System.currentTimeMillis();
        try {
            if(now>=discoverAfter) {
                discoverAfter=now+properties.getRecheckInterval().toMillis();
                for(var c:repository.discover())repository.registerDiscovered(c,now);
            }
            for(var h:repository.due(now)) {
                String token=null;
                try {
                    String current=repository.sourceVersion(h.building(),YearMonth.parse(h.month()));
                    if("READY".equals(h.state()) && current.equals(h.builtVersion())){repository.checked(h,now);continue;}
                    if(!current.equals(h.sourceVersion())){repository.request(h.building(),h.month(),h.user(),current,now);h=repository.head(h.building(),h.month());}
                    token=repository.claim(h,now);if(token==null)continue;
                    // 任务仅保存发起人ID；执行时读取当前账号和角色，不能持久化旧管理员角色绕过撤权。
                    if(!AuthenticationStateService.isEnabled(users.selectSecurityState(h.user())))throw new BusinessException(403,"日趋势后台发起人不可用");
                    var currentRoles=roles.selectRoleKeysByUserId(h.user()).stream().map(v->v.toUpperCase(Locale.ROOT))
                            .filter(Set.of("BUILDING_OWNER","ENERGY_MANAGER","PLATFORM_ADMIN")::contains).toList();
                    if(currentRoles.isEmpty())throw new BusinessException(403,"日趋势后台发起人没有读取职责");
                    service.rebuild(h,token,currentRoles);
                } catch(RuntimeException e) {
                    String error=e instanceof BusinessException b && b.getErrorCode()!=null?b.getErrorCode():"DAILY_SUMMARY_REFRESH_FAILED";
                    if(token!=null)repository.failed(h,token,error,System.currentTimeMillis());
                    else repository.defer(h,error,System.currentTimeMillis());
                    LoggerFactory.getLogger(getClass()).warn("Daily trend refresh failed: building={}, month={}, code={}",h.building(),h.month(),error);
                    continue;
                }
                return;
            }
        } catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Daily trend refresh scan unavailable",e);}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){worker.shutdownNow();}
}
