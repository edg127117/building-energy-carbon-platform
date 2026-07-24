package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.model.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 角色与动态菜单关联表的数据访问接口。 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {

    /**
     * 三表联查：通过角色 key 查询该角色拥有的可见、启用菜单。
     */
    @Select("SELECT sm.* FROM sys_menu sm INNER JOIN sys_role_menu srm ON sm.id = srm.menu_id INNER JOIN sys_role sr ON sr.id = srm.role_id WHERE sr.role_key = #{roleKey} AND sm.status = 1 AND sm.visible = 1 ORDER BY sm.sort_order")
    List<SysMenu> selectMenusByRoleKey(String roleKey);

    /** 查询角色当前关联的菜单 ID，供管理端回显勾选状态。 */
    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id=#{roleId} ORDER BY menu_id")
    List<Long> selectMenuIdsByRoleId(Long roleId);
}
