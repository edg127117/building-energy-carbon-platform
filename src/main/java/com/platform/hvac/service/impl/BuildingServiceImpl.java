package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BuildingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * 建筑档案的 MySQL 查询与维护实现。
 *
 * <p>上游包括建筑 Controller 和数据质量重算服务：前者传入已解析的建筑范围进行
 * 分页或管理，后者在自己的事务中调用行锁方法保护同建筑任务受理。结果返回 HTTP
 * 调用方或作为跨模块锁边界使用；本类不计算用户权限，也不访问 TDengine。</p>
 */
@Slf4j
@Service
public class BuildingServiceImpl extends ServiceImpl<BuildingMapper, Building> implements BuildingService {

    /**
     * 通过建筑主表行锁保护后续“检查重叠任务并创建任务”的事务区间。
     */
    @Override
    public void lockExistingForUpdate(String buildingId) {
        if (baseMapper.selectExistingForUpdate(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
    }

    /**
     * 使用调用方给定的建筑范围查询 MySQL，并按创建时间倒序返回分页结果。
     *
     * <p>{@code accessibleBuildingIds} 为空集合时直接返回空页，{@code null} 时不添加
     * 范围条件；后者只应由已完成角色判断的平台管理员调用链传入。</p>
     */
    @Override
    public Result<IPage<Building>> list(Integer page, Integer size, String keyword, Set<String> accessibleBuildingIds) {
        Page<Building> pageParam = new Page<>(page, size);
        if (accessibleBuildingIds != null && accessibleBuildingIds.isEmpty()) return Result.success(pageParam);
        LambdaQueryWrapper<Building> wrapper = new LambdaQueryWrapper<>();
        if (accessibleBuildingIds != null) wrapper.in(Building::getBuildingId, accessibleBuildingIds);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Building::getBuildingName, keyword)
                    .or().like(Building::getBuildingCode, keyword));
        }
        wrapper.orderByDesc(Building::getCreateTime);
        IPage<Building> result = this.page(pageParam, wrapper);
        return Result.success(result);
    }

    /** 将 Controller 校验后的建筑实体保存到 MySQL，并把持久化后的实体返回调用方。 */
    @Override
    public Result<Building> add(Building building) {
        this.save(building);
        return Result.success(building);
    }

    /** 按主键更新 MySQL 建筑档案；权限和请求字段校验由上游入口负责。 */
    @Override
    public Result<Building> update(Building building) {
        this.updateById(building);
        return Result.success(building);
    }

    /** 通过实体的 {@code @TableLogic} 标记建筑已删除，不物理清除关联记录。 */
    @Override
    public Result<Void> delete(String buildingId) {
        // @TableLogic 自动将 del_flag 从 0 置为 1，不走物理删除
        this.removeById(buildingId);
        return Result.success();
    }
}
