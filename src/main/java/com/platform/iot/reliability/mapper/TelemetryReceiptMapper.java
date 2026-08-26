package com.platform.iot.reliability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDateTime;

@Mapper
/** V2 终态回执的 MySQL 入口；成功 ACK 证据只进入监控系统。 */
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

    @Delete("""
            DELETE FROM biz_telemetry_receipt
            WHERE persisted_at < #{before}
              AND NOT EXISTS (
                SELECT 1
                FROM biz_telemetry_receipt_failure f
                WHERE f.canonical_message_id =
                      biz_telemetry_receipt.canonical_message_id
              )
            ORDER BY persisted_at, canonical_message_id
            LIMIT #{limit}
            """)
    int deleteExpiredWithoutFailure(@Param("before") LocalDateTime before,
                                    @Param("limit") int limit);

    @Delete("""
            DELETE FROM biz_telemetry_receipt
            WHERE persisted_at < #{before}
              AND EXISTS (
                SELECT 1
                FROM biz_telemetry_receipt_failure f
                WHERE f.canonical_message_id =
                      biz_telemetry_receipt.canonical_message_id
              )
              AND NOT EXISTS (
                SELECT 1
                FROM biz_telemetry_receipt_failure recent
                WHERE recent.canonical_message_id =
                      biz_telemetry_receipt.canonical_message_id
                  AND recent.occurred_at >= #{before}
              )
            ORDER BY persisted_at, canonical_message_id
            LIMIT #{limit}
            """)
    int deleteExpiredWithFailure(@Param("before") LocalDateTime before,
                                 @Param("limit") int limit);

    @Select("""
            SELECT MIN(persisted_at)
            FROM biz_telemetry_receipt
            WHERE persisted_at < #{before}
              AND NOT EXISTS (
                SELECT 1
                FROM biz_telemetry_receipt_failure f
                WHERE f.canonical_message_id =
                      biz_telemetry_receipt.canonical_message_id
              )
            """)
    LocalDateTime selectOldestDeletablePersistedAt(@Param("before") LocalDateTime before);
}
