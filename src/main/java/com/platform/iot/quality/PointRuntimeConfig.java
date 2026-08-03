package com.platform.iot.quality;

import java.math.BigDecimal;

/**
 * 从 MySQL 测点、设备和别名配置组装出的不可变运行快照。
 *
 * <p>采集校验使用状态、设备归属及数值边界；分钟聚合使用数据类型和计算标记；
 * 公式组装使用建筑、设备、组件/族和后缀生成语义键。把这些字段放在同一快照中，
 * 可保证一条事件从身份校验到指标依赖解析采用同一套标准元数据。</p>
 *
 * @param pointId 平台内部测点 ID，也是 TDengine 子表身份的一部分
 * @param pointCode 平台标准点码，不等同于外部来源点码
 * @param pointName 测点显示名称
 * @param buildingId 所属建筑 ID
 * @param systemGroupId 所属系统组，可为空
 * @param equipId 所属设备 ID；建筑环境点可为空
 * @param equipCode 设备上报编码；用于非环境点的 deviceId 归属校验
 * @param familyCode 设备族或环境族编码
 * @param componentCode 设备组件编码；环境点可为空
 * @param suffixCode 物理量后缀，与组件/族共同组成公式语义键
 * @param dataType MySQL 数据类型；分钟计算点要求为 ANALOG
 * @param unit MySQL 测点单位；本记录只承载，不在采集校验中执行单位换算
 * @param status 运行状态；只有 ONLINE 可进入正式分钟
 * @param isForCalc 是否允许参与公式计算，1 表示允许
 * @param valueMin 接入允许的最小值，可为空
 * @param valueMax 接入允许的最大值，可为空
 */
public record PointRuntimeConfig(
        String pointId,
        String pointCode,
        String pointName,
        String buildingId,
        String systemGroupId,
        String equipId,
        String equipCode,
        String familyCode,
        String componentCode,
        String suffixCode,
        String dataType,
        String unit,
        String status,
        int isForCalc,
        BigDecimal valueMin,
        BigDecimal valueMax
) {
    /**
     * 构造不参与数据类型和单位判断的最小运行配置。
     *
     * <p>该重载把 {@code dataType}、{@code unit} 留空；需要筛选模拟量或校验计算单位
     * 的流程必须使用完整构造器，不能把空值解释为 MySQL 已配置。</p>
     */
    public PointRuntimeConfig(
            String pointId,
            String pointCode,
            String pointName,
            String buildingId,
            String systemGroupId,
            String equipId,
            String equipCode,
            String familyCode,
            String componentCode,
            String suffixCode,
            String status,
            int isForCalc,
            BigDecimal valueMin,
            BigDecimal valueMax) {
        this(pointId, pointCode, pointName, buildingId, systemGroupId, equipId,
                equipCode, familyCode, componentCode, suffixCode, null, null,
                status, isForCalc, valueMin, valueMax);
    }
}
