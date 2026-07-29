package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.Building;
import java.util.Set;

/**
 * 建筑业务接口
 */
public interface BuildingService extends IService<Building> {

    /**
     * 在调用方 MySQL 事务内锁定现有建筑。
     *
     * <p>数据质量模块通过该业务边界串行化同一建筑的重算任务，不能跨模块直接
     * 调用建筑 Mapper。</p>
     *
     * @throws com.platform.framework.exception.BusinessException 建筑不存在时返回 404
     */
    void lockExistingForUpdate(String buildingId);

    /**
     * 按名称/编码模糊搜索分页查询建筑列表
     */
    Result<IPage<Building>> list(Integer page, Integer size, String keyword, Set<String> accessibleBuildingIds);

    /**
     * 新增建筑
     */
    Result<Building> add(Building building);

    /**
     * 更新建筑
     */
    Result<Building> update(Building building);

    /**
     * 逻辑删除建筑
     */
    Result<Void> delete(String buildingId);
}
