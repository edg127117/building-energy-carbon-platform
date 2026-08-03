package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.Building;
import java.util.Set;

/**
 * 建筑档案的业务边界。
 *
 * <p>建筑 Controller 用它完成 MySQL 档案维护和范围内分页查询；数据质量模块也通过
 * 行锁方法串行化同一建筑的重算受理。实现不计算建筑权限，调用方必须传入已经解析的
 * 可访问建筑集合或在调用前完成单建筑校验。</p>
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
     * 按名称或编码分页查询可访问建筑。
     *
     * @param accessibleBuildingIds {@code null} 表示平台管理员不过滤，空集合表示无权建筑
     */
    Result<IPage<Building>> list(Integer page, Integer size, String keyword, Set<String> accessibleBuildingIds);

    /** 将调用方校验后的建筑档案写入 MySQL。 */
    Result<Building> add(Building building);

    /** 按建筑 ID 更新 MySQL 档案。 */
    Result<Building> update(Building building);

    /** 通过 MyBatis-Plus 逻辑删除建筑，不清理关联时序数据。 */
    Result<Void> delete(String buildingId);
}
