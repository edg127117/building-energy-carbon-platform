package com.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.model.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色 Mapper
 * 这里额外提供按用户查询启用角色的查询，用于 JWT claims、人员视图和管理员安全保护。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询用户的启用角色标识列表（示例：["BUILDING_OWNER"]）
     */
    @Select("""
            SELECT r.role_key
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1
            """)
    List<String> selectRoleKeysByUserId(Long userId);

    /** 查询用户关联的启用角色 ID。 */
    @Select("""
            SELECT r.id FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id=r.id
            WHERE ur.user_id=#{userId} AND r.status=1
            """)
    List<Long> selectRoleIdsByUserId(Long userId);
}
