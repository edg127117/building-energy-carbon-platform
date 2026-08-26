package com.platform.audit;

/** 动态后台职责服务；每次判定以 MySQL 当前授权为准，不依赖 JWT 快照。 */
public interface BackendDutyService {
    boolean hasDuty(long userId, BackendDuty duty);

    void requireDuty(long userId, BackendDuty duty);

    void requireSeparation(long submitterId, long reviewerId);

    /** 为后续可选缓存保留失效边界；首版直接查询 MySQL，因此实现可为空操作。 */
    void evict(long userId);
}
