package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSystemGroup;
import java.util.Set;

/**
 * 系统组业务接口
 */
public interface BizSystemGroupService extends IService<BizSystemGroup> {

    /**
     * 分页查询系统组列表，支持按建筑和关键字筛选
     */
    Result<IPage<BizSystemGroup>> list(Integer page, Integer size, String buildingId, String keyword, Set<String> accessibleBuildingIds);

    /**
     * 新增系统组
     */
    Result<BizSystemGroup> add(BizSystemGroup group);

    /**
     * 更新系统组
     */
    Result<BizSystemGroup> update(BizSystemGroup group);

    /**
     * 删除系统组
     */
    Result<Void> delete(String systemGroupId);
}
