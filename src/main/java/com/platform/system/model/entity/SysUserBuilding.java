package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户与建筑的授权关联。
 *
 * <p>非平台管理员能访问哪些建筑由本表决定。一个用户可关联多个建筑，
 * 一个建筑也可授权给多个用户；数据库通过用户 ID 与建筑 ID 的唯一索引避免重复授权。</p>
 */
@Data
@TableName("sys_user_building")
public class SysUserBuilding {
    /** 关联记录主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 被授权用户 ID，对应 {@code sys_user.id}。 */
    private Long userId;
    /** 可访问建筑 ID，对应 {@code building.building_id}。 */
    private String buildingId;
    /** 授权关系创建时间。 */
    private Date createTime;
}
