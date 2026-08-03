package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * MySQL {@code sys_user} 中的登录账号。
 *
 * <p>实体保存身份、密码哈希和账号状态，不直接保存角色、菜单或建筑范围；这些授权关系
 * 分别通过 {@code sys_user_role}、角色菜单和用户建筑关联查询，避免账号行承担多种权限语义。</p>
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希；登录服务只为受控历史明文执行一次成功登录后的升级。 */
    private String password;

    private String nickname;

    private String phone;

    /** 账号状态：1 可登录，0 禁用。 */
    private Integer status;

    /** 0-正常，1-逻辑删除 */
    @TableLogic
    private Integer delFlag;

    private Date createTime;

    private Date updateTime;
}
