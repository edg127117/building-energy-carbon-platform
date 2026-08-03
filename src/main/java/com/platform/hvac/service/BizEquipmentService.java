package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizEquipment;
import java.util.Set;

/**
 * HVAC 设备台账的业务边界。
 *
 * <p>Controller 使用本接口查询和维护 MySQL 设备档案；实现负责设备类型、建筑、
 * 系统分组和空间关系以及业务编码分配。调用方仍负责角色和建筑数据范围校验，
 * 本接口不读取设备时序值。</p>
 */
public interface BizEquipmentService extends IService<BizEquipment> {

    /**
     * 在授权建筑集合内分页查询设备，可按建筑、设备分类和名称/编码筛选。
     *
     * @param accessibleBuildingIds {@code null} 表示不过滤，空集合返回空页
     */
    Result<IPage<BizEquipment>> list(Integer page, Integer size, String buildingId, String equipCategory, String keyword, Set<String> accessibleBuildingIds);

    /** 校验 MySQL 关系后新增设备，内部 ID 和建筑内业务编码均由后端生成。 */
    Result<BizEquipment> add(BizEquipment equipment);

    /** 更新设备可编辑字段，保持身份、建筑、类型、分类和业务编码不变。 */
    Result<BizEquipment> update(BizEquipment equipment);

    /** 逻辑删除设备，不级联清理测点或 TDengine 数据。 */
    Result<Void> delete(String equipId);
}
