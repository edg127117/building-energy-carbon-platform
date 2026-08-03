package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * MySQL {@code sys_user_role} 的账号与正式角色关联记录。
 *
 * <p>登录服务通过该关系解析 JWT 中的正式角色；记录本身不包含菜单和建筑权限，
 * 菜单由角色菜单关系决定，建筑查看范围由用户建筑关系单独维护。</p>
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code sys_user.id}。 */
    private Long userId;

    /** {@code sys_role.id}。 */
    private Long roleId;

    /** 关联建立时间。 */
    private Date createTime;
}
