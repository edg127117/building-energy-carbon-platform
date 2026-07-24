package com.platform.hvac.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSpace;

import java.util.List;

/**
 * 空间业务接口
 */
public interface BizSpaceService extends IService<BizSpace> {

    /**
     * 查某建筑下所有空间
     */
    Result<List<BizSpace>> listByBuilding(String buildingId);

    /**
     * 构建树形结构（parentSpaceId递归），根节点 parentSpaceId=NULL
     */
    Result<List<BizSpace>> tree(String buildingId);

    /**
     * 新增空间
     */
    Result<BizSpace> add(BizSpace space);

    /**
     * 更新空间
     */
    Result<BizSpace> update(BizSpace space);

    /**
     * 删除空间
     */
    Result<Void> delete(String spaceId);
}
