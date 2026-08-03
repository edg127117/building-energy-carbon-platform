package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MySQL {@code sys_role_menu} 的角色与菜单关联记录。
 *
 * <p>角色管理服务以全量替换方式维护该表，并在关系变化后清理受影响用户的菜单缓存；
 * 这里不表达后端接口权限，接口权限仍由正式角色和 Spring Security 校验。</p>
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code sys_role.id}。 */
    private Long roleId;

    /** {@code sys_menu.id}。 */
    private Long menuId;
}
