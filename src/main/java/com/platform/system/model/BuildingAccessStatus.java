package com.platform.system.model;

/**
 * 建筑访问申请的状态机。
 *
 * <p>只有 {@link #PENDING} 可以被申请人取消或被平台管理员审核；
 * 审批、拒绝和取消都是终态，不能重复处理。</p>
 */
public enum BuildingAccessStatus {
    /** 已提交，等待平台管理员处理。 */
    PENDING,
    /** 审批通过，并已写入用户建筑授权关系。 */
    APPROVED,
    /** 平台管理员拒绝申请。 */
    REJECTED,
    /** 申请人在审核前主动取消。 */
    CANCELLED
}
