package com.platform.iot.reliability.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_mqtt_failure_aggregate")
/** 按分钟和稳定分类聚合的 MQTT/TLS 故障，不保存凭据或证书内容。 */
public class MqttFailureAggregate {
    @TableId
    private String aggregateId;
    private LocalDateTime bucketStart;
    private String component;
    private String failureCategory;
    private String brokerEndpoint;
    private Long occurrenceCount;
    private LocalDateTime firstOccurredAt;
    private LocalDateTime lastOccurredAt;
}
