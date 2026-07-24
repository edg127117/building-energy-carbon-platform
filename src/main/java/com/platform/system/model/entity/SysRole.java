package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleKey;

    private String roleName;

    private Integer status;

    /** 数据范围：ALL-全部, BUILDING-按建筑, SELF-仅自己 */
    private String dataScope;

    private Date createTime;

    private Date updateTime;
}
