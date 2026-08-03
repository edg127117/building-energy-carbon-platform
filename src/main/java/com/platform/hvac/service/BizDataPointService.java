package com.platform.hvac.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizDataPoint;

import java.util.List;

/**
 * HVAC 标准测点档案的业务边界。
 *
 * <p>Controller 完成角色和建筑范围校验后调用本接口；实现访问 MySQL，校验标准
 * 命名、设备/系统归属和计算单位，并在写操作成功后刷新 MQTT 与质量链使用的
 * 内存配置快照。该接口不读取或删除 TDengine 分钟数据。</p>
 */
public interface BizDataPointService extends IService<BizDataPoint> {

    /** 返回一个设备的全部 MySQL 测点档案。 */
    Result<List<BizDataPoint>> listByEquip(String equipId);

    /** 返回一个建筑的全部 MySQL 测点档案。 */
    Result<List<BizDataPoint>> listByBuilding(String buildingId);

    /** 校验调用方填写的标准编码和关联关系后新增测点，并刷新配置快照。 */
    Result<BizDataPoint> add(BizDataPoint point);

    /** 保持标准身份字段不变，校验最终状态后更新测点并刷新配置快照。 */
    Result<BizDataPoint> update(BizDataPoint point);

    /** 按内部 ID 逻辑删除测点并刷新配置快照。 */
    Result<Void> delete(String pointId);
}
