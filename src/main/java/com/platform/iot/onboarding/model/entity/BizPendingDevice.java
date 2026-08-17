package com.platform.iot.onboarding.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("biz_pending_device")
/**
 * 未完成本地业务绑定的标准设备发现记录。
 *
 * <p>该表只保存上游已经限界的规范化指标样例，不保存原始厂商报文、建筑归属或正式时序。
 * 身份组合键的并发上报必须经原子 upsert 累加计数，并且不得把 IGNORED 或 BOUND 自动改回
 * DISCOVERED。</p>
 */
public class BizPendingDevice implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 自定义 upsert 必须由调用方提前分配待绑定记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String pendingId;
    private String identityType;
    private String identityValue;
    private String profileCode;
    private Integer lastProfileVersion;
    private LocalDateTime firstSeenTime;
    private LocalDateTime lastSeenTime;
    private Long reportCount;
    private LocalDateTime latestEventTime;
    /** DEVICE_REPORTED 或 SERVER_RECEIVED，必须与事件时间一同解释。 */
    private String latestTimeSource;
    /** 已被上游字段数、字符串长度和总大小限制后的规范化指标样例。 */
    private String latestMetricsJson;
    private Integer sampleTruncated;
    /** DISCOVERED、BOUND、IGNORED；上报更新不得改变该状态。 */
    private String status;
    private String boundIdentityId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
