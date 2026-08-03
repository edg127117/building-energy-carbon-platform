package com.platform.hvac.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSpace;

import java.util.List;

/**
 * 建筑空间档案的业务边界。
 *
 * <p>Controller 完成建筑权限校验后调用本接口；实现从 MySQL 读取空间、组装父子树，
 * 并在写入时约束父空间归属。该接口不负责设备迁移或级联删除。</p>
 */
public interface BizSpaceService extends IService<BizSpace> {

    /** 按楼层排序返回一个建筑的全部空间。 */
    Result<List<BizSpace>> listByBuilding(String buildingId);

    /** 按 {@code parentSpaceId} 递归组装空间树，根节点的父 ID 为 {@code null}。 */
    Result<List<BizSpace>> tree(String buildingId);

    /** 新增空间，并校验非空父空间存在且属于同一建筑。 */
    Result<BizSpace> add(BizSpace space);

    /** 更新空间可编辑字段，保持原建筑归属并重新校验父空间。 */
    Result<BizSpace> update(BizSpace space);

    /** 逻辑删除空间；不级联处理子空间或设备。 */
    Result<Void> delete(String spaceId);
}
