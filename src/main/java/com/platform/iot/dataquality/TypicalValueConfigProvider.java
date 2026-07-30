package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;

import java.util.List;
import java.util.Optional;

/**
 * 质量补全运行链路读取已批准典型值的快照边界。
 *
 * <p>调用方只能通过本接口取得已经审批且在目标分钟生效的版本，不得读取测点
 * {@code default_value} 或草稿配置作为质量 2 数据。</p>
 */
public interface TypicalValueConfigProvider {

    /** 按项目时区查找在目标分钟生效的批准版本，区间语义为 {@code [validFrom, validTo)}。 */
    Optional<BizPointTypicalValueConfig> findApproved(String pointId, long minuteStart);

    /** 返回当前不可变完整快照。 */
    List<BizPointTypicalValueConfig> snapshot();

    /** 从 MySQL 重新加载完整快照；加载失败时实现必须保留上一份成功快照。 */
    void refresh();
}
