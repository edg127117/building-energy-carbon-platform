package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@TableName("biz_space")
public class BizSpace implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 空间ID，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private String spaceId;

    /** 外键→building.building_id */
    private String buildingId;

    /** 父空间ID，顶级=0 */
    private String parentSpaceId;

    /** 如"核心冷源机房A" */
    private String spaceName;

    /** 如F-B2-RM01 */
    private String spaceCode;

    /** FLOOR/ZONE/ROOM */
    private String spaceType;

    /** 楼层排序值 */
    private Integer floorLevel;

    /** 实用面积(m²) */
    private BigDecimal usableArea;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 创建人ID */
    private String createBy;

    /** 更新人ID */
    private String updateBy;

    /** 0-正常, 1-已删除 */
    @TableLogic
    private Integer delFlag;

    /** 子节点（非数据库字段，树形查询用） */
    @TableField(exist = false)
    private List<BizSpace> children;
}
