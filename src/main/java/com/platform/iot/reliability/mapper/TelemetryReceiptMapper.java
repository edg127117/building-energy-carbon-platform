package com.platform.iot.reliability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

@Mapper
/** V2 终态回执和投递证据的 MySQL 入口。 */
public interface TelemetryReceiptMapper extends BaseMapper<TelemetryReceipt> {

    @Select("SELECT * FROM biz_telemetry_receipt WHERE canonical_message_id=#{id} FOR UPDATE")
    TelemetryReceipt selectForUpdate(@Param("id") String canonicalMessageId);

    @Update("""
            UPDATE biz_telemetry_receipt
            SET attempt_count=attempt_count+1,
                last_platform_received_at=#{receivedAt},
                batch_id=COALESCE(#{batchId}, batch_id),
                retransmitted_at=COALESCE(#{retransmittedAt}, retransmitted_at)
            WHERE canonical_message_id=#{id}
            """)
    int incrementAttempt(@Param("id") String canonicalMessageId,
                         @Param("receivedAt") java.time.LocalDateTime receivedAt,
                         @Param("batchId") String batchId,
                         @Param("retransmittedAt") java.time.LocalDateTime retransmittedAt);

    @Update("""
            UPDATE biz_telemetry_receipt
            SET platform_consumer_ack_state='OBSERVED',
                application_ack_puback_state=#{applicationAckState},
                application_ack_published_at=#{applicationAckPublishedAt}
            WHERE canonical_message_id=#{id}
            """)
    int markDeliveryCompleted(@Param("id") String canonicalMessageId,
                              @Param("applicationAckState") String applicationAckState,
                              @Param("applicationAckPublishedAt") java.time.LocalDateTime publishedAt);

    @Delete("DELETE FROM biz_telemetry_receipt WHERE persisted_at < #{before} LIMIT #{limit}")
    int deleteExpired(@Param("before") java.time.LocalDateTime before,
                      @Param("limit") int limit);
}
