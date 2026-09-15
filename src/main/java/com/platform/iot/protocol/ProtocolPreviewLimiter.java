package com.platform.iot.protocol;

import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** 单体内按管理员限流预览与字段检查；有界桶不保存样例或设备身份。 */
@Component
public class ProtocolPreviewLimiter {
    private final int perMinute;
    private final Map<Long,Bucket> buckets=new HashMap<>();
    private record Bucket(long minute,int count) {}
    public ProtocolPreviewLimiter(@Value("${protocol-preview.requests-per-minute:60}") int perMinute) {
        if(perMinute<1) throw new IllegalArgumentException("预览频率必须为正数");
        this.perMinute=perMinute;
    }
    public synchronized void acquire(Long user) {
        long minute=System.currentTimeMillis()/60_000;
        buckets.entrySet().removeIf(e->e.getValue().minute()!=minute);
        var current=buckets.getOrDefault(user,new Bucket(minute,0));
        if(current.count()>=perMinute || (!buckets.containsKey(user) && buckets.size()>=4096))
            throw new BusinessException(429,"PROTOCOL_PREVIEW_RATE_LIMIT","预览请求过于频繁，请稍后重试");
        buckets.put(user,new Bucket(minute,current.count()+1));
    }
}
