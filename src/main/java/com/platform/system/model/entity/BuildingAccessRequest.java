package com.platform.system.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户申请查看某栋建筑的审核记录。
 *
 * <p>记录保留申请原因和完整审核轨迹。审批通过后，业务服务会同时向
 * {@code sys_user_building} 写入正式授权，并清理该用户的建筑范围缓存。</p>
 */
@Data
@TableName("sys_building_access_request")
public class BuildingAccessRequest {
    /** 申请主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 申请人用户 ID。 */
    private Long userId;
    /** 申请访问的建筑 ID。 */
    private String buildingId;
    /** 申请人填写的用途或原因。 */
    private String reason;
    /** 状态值，取自 {@code BuildingAccessStatus}。 */
    private String status;
    /** 最终审核的平台管理员用户 ID。 */
    private Long reviewerId;
    /** 审核意见，可为空。 */
    private String reviewComment;
    /** 审核完成时间。 */
    private Date reviewTime;
    /** 申请提交时间。 */
    private Date createTime;
    /** 申请最后更新时间。 */
    private Date updateTime;
}
