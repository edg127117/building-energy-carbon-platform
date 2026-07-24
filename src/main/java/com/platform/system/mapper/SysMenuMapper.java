package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 动态菜单的数据访问接口。 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
    /**
     * 根据“用户 → 正式角色 → 菜单”关系查询可见且启用的菜单并去重。
     * 父级目录由菜单服务在构树前补齐。
     */
    @Select("""
            SELECT DISTINCT m.* FROM sys_menu m
            JOIN sys_role_menu rm ON rm.menu_id=m.id
            JOIN sys_role r ON r.id=rm.role_id
            JOIN sys_user_role ur ON ur.role_id=r.id
            WHERE ur.user_id=#{userId} AND r.status=1
              AND r.role_key IN ('BUILDING_OWNER','ENERGY_MANAGER','THIRD_PARTY','PLATFORM_ADMIN')
              AND m.status=1 AND m.visible=1
            ORDER BY m.sort_order, m.id
            """)
    List<SysMenu> selectVisibleMenusByUserId(Long userId);
}
