package com.platform.hvac.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizDataPoint;

import java.util.List;

/**
 * 数据测点业务接口
 */
public interface BizDataPointService extends IService<BizDataPoint> {

    /**
     * 查某设备下所有测点
     */
    Result<List<BizDataPoint>> listByEquip(String equipId);

    /**
     * 查某建筑下所有测点
     */
    Result<List<BizDataPoint>> listByBuilding(String buildingId);

    /**
     * 新增测点（pointCode 手动填入）
     */
    Result<BizDataPoint> add(BizDataPoint point);

    /**
     * 更新测点
     */
    Result<BizDataPoint> update(BizDataPoint point);

    /**
     * 按内部ID删除测点
     */
    Result<Void> delete(String pointId);
}
