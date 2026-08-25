package com.platform.iot.reliability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.reliability.model.MqttFailureAggregate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
/** MQTT/TLS 分钟聚合故障的 MySQL 入口。 */
public interface MqttFailureAggregateMapper extends BaseMapper<MqttFailureAggregate> {

    @Insert("""
            INSERT INTO biz_mqtt_failure_aggregate
              (aggregate_id,bucket_start,component,failure_category,broker_endpoint,
               occurrence_count,first_occurred_at,last_occurred_at)
            VALUES
              (#{aggregateId},#{bucketStart},#{component},#{failureCategory},#{brokerEndpoint},
               #{occurrenceCount},#{firstOccurredAt},#{lastOccurredAt})
            ON DUPLICATE KEY UPDATE
              occurrence_count=occurrence_count+VALUES(occurrence_count),
              first_occurred_at=LEAST(first_occurred_at,VALUES(first_occurred_at)),
              last_occurred_at=GREATEST(last_occurred_at,VALUES(last_occurred_at))
            """)
    int upsert(MqttFailureAggregate aggregate);

    @Delete("DELETE FROM biz_mqtt_failure_aggregate WHERE bucket_start < #{before} LIMIT #{limit}")
    int deleteExpired(@Param("before") java.time.LocalDateTime before,
                      @Param("limit") int limit);
}
