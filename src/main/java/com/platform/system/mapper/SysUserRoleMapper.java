package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用户角色关联表的数据访问接口。 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
    /** 查询拥有指定角色的用户，用于角色菜单变化后的定向缓存失效。 */
    @Select("SELECT user_id FROM sys_user_role WHERE role_id=#{roleId}")
    List<Long> selectUserIdsByRoleId(Long roleId);
}
