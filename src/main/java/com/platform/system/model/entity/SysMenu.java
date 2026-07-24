package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 菜单ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父菜单ID */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** M-目录, C-菜单, F-按钮 */
    private String menuType;

    /** 前端路由地址 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 权限标识 */
    private String perms;

    /** 图标 */
    private String icon;

    /** 0-隐藏, 1-显示 */
    private Integer visible;

    /** 0-停用, 1-正常 */
    private Integer status;

    /** 排序 */
    private Integer sortOrder;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 树形子节点，非数据库字段 */
    @TableField(exist = false)
    private List<SysMenu> children;
}
