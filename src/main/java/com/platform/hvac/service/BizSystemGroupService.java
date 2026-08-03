package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSystemGroup;
import java.util.Set;

/**
 * 建筑内 HVAC 系统分组档案的业务边界。
 *
 * <p>Controller 传入用户可访问建筑集合，Service 实现据此访问 MySQL；设备和测点
 * 档案仅通过系统分组 ID 建立归属。本接口不维护其下设备，也不读取运行数据。</p>
 */
public interface BizSystemGroupService extends IService<BizSystemGroup> {

    /**
     * 在授权建筑集合内分页查询系统分组，可再按建筑和名称/编码筛选。
     *
     * @param accessibleBuildingIds {@code null} 表示不过滤，空集合返回空页
     */
    Result<IPage<BizSystemGroup>> list(Integer page, Integer size, String buildingId, String keyword, Set<String> accessibleBuildingIds);

    /** 清除客户端内部 ID 后新增系统分组。 */
    Result<BizSystemGroup> add(BizSystemGroup group);

    /** 更新可编辑字段，保持原建筑归属和业务编码不变。 */
    Result<BizSystemGroup> update(BizSystemGroup group);

    /** 逻辑删除系统分组，不级联删除设备或测点。 */
    Result<Void> delete(String systemGroupId);
}
