package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * MySQL {@code sys_role} 中的角色定义。
 *
 * <p>{@code roleKey} 是登录授权和 {@code @PreAuthorize} 使用的稳定标识；角色菜单通过
 * {@code sys_role_menu} 维护，具体用户归属通过 {@code sys_user_role} 维护。</p>
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 与 {@code FormalRole} 对应的稳定英文键。 */
    private String roleKey;

    private String roleName;

    /** 角色状态：1 启用，0 停用。 */
    private Integer status;

    /** 数据范围：ALL-全部, BUILDING-按建筑, SELF-仅自己 */
    private String dataScope;

    private Date createTime;

    private Date updateTime;
}
