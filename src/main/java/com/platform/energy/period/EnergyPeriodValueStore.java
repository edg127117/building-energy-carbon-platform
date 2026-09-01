package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.NumericResult;

/**
 * 周期数值幂等写入端口；MySQL 只在此端口成功后发布对应索引。
 * 若后续 MySQL 事务失败，重试会写回相同 TDengine 子表和时间戳，不追加重复数值。
 */
public interface EnergyPeriodValueStore {
    void write(NumericResult result);
}
