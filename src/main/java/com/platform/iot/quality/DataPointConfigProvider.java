package com.platform.iot.quality;

import java.util.Collection;
import java.util.Optional;

/**
 * 采集、分钟聚合和公式依赖解析共用的标准测点配置边界。
 *
 * <p>MQTT 校验按外部别名查单点，迟到修正按内部 ID 定位点位，分钟聚合与公式
 * 依赖筛选读取同一完整快照。调用方不直接访问 MySQL Mapper，也不关心实现是否使用
 * 内存缓存；实现可以原子发布完整快照，也可以在自动化测试中替换为可控数据。</p>
 */
public interface DataPointConfigProvider {

    /** 按“建筑 + 可信来源系统 + 外部点码”解析标准测点。 */
    Optional<PointRuntimeConfig> find(PointAliasKey aliasKey);

    /** 按平台内部测点 ID 查询当前快照；未实现该能力的测试替身返回空。 */
    default Optional<PointRuntimeConfig> findByPointId(String pointId) {
        return Optional.empty();
    }

    /**
     * 返回一次完整快照中的全部测点，供分钟聚合和公式依赖筛选；调用方再按状态与
     * {@code isForCalc} 判断是否参与对应流程。
     */
    default Collection<PointRuntimeConfig> findAll() {
        return java.util.List.of();
    }
}
