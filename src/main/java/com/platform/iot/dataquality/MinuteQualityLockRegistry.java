package com.platform.iot.dataquality;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 为同一测点、同一分钟的质量比较与写入提供进程内互斥。
 *
 * <p>固定条带不会为每个历史分钟永久保存锁对象。多个键映射到同一条带时只加锁一次，
 * 并按条带序号统一排序，避免两个批次以不同顺序拿锁形成死锁。V1 仍依赖 TDengine
 * 幂等时间戳写入兜底；多实例部署时应替换为跨进程锁。</p>
 */
@Component
public class MinuteQualityLockRegistry {

    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public MinuteQualityLockRegistry() {
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    public <T> T withLocks(
            Collection<MinuteKey> minuteKeys, Supplier<T> protectedOperation) {
        List<Integer> stripes = minuteKeys.stream()
                .sorted(Comparator.comparing(MinuteKey::pointId)
                        .thenComparingLong(MinuteKey::minuteStart))
                .map(this::stripeIndex)
                .distinct()
                .sorted()
                .toList();
        stripes.forEach(index -> locks[index].lock());
        try {
            return protectedOperation.get();
        } finally {
            for (int index = stripes.size() - 1; index >= 0; index--) {
                locks[stripes.get(index)].unlock();
            }
        }
    }

    private int stripeIndex(MinuteKey key) {
        return Math.floorMod(31 * key.pointId().hashCode()
                + Long.hashCode(key.minuteStart()), STRIPE_COUNT);
    }

    public record MinuteKey(String pointId, long minuteStart) {
    }
}
