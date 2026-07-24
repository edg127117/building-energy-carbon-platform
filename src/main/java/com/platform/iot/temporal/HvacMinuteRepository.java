package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.List;
import java.util.Set;

/**
 * 正式分钟汇总存储边界。
 */
public interface HvacMinuteRepository {
    /**
     * 一次保存同一分钟的多个测点结果。
     */
    void saveAll(List<RawMinuteAggregate> aggregates);

    /**
     * 一次读取某分钟已经存在的全部内部测点ID，仅供恢复任务判断缺口。
     */
    Set<String> findExistingPointIds(long minuteStart);
}
