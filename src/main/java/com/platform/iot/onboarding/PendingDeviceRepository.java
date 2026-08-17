package com.platform.iot.onboarding;

import java.time.LocalDateTime;

/** 本地 MySQL 待绑定设备的原子发现与有界清理端口。 */
public interface PendingDeviceRepository {

    /** 按外部身份组合键插入或原子累加发现次数，不改写已有业务状态。 */
    void upsertDiscovery(PendingDeviceDiscovery discovery);

    /** 只删除截止时间前的 DISCOVERED、IGNORED 记录，返回本批实际删除数。 */
    int deleteExpired(LocalDateTime cutoffExclusive, int limit);
}
