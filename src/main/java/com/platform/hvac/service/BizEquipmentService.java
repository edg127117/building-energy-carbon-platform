package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizEquipment;
import java.util.Set;

/**
 * 设备业务接口
 */
public interface BizEquipmentService extends IService<BizEquipment> {

    /**
     * 分页查询设备列表，支持按建筑、设备子类、关键字筛选
     */
    Result<IPage<BizEquipment>> list(Integer page, Integer size, String buildingId, String equipCategory, String keyword, Set<String> accessibleBuildingIds);

    /**
     * 新增设备（内部ID和建筑内业务编码均由后端生成）
     */
    Result<BizEquipment> add(BizEquipment equipment);

    /**
     * 更新设备
     */
    Result<BizEquipment> update(BizEquipment equipment);

    /**
     * 删除设备
     */
    Result<Void> delete(String equipId);
}
