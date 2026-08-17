package com.platform.iot.onboarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
/**
 * 待绑定设备发现记录的 MySQL 持久化入口。
 *
 * <p>同一外部身份可能被多个 MQTT 消费线程同时发现，必须使用数据库唯一键与原子 upsert
 * 累加上报次数。未知上报只能刷新样例和最近发现信息，绝不能改写人工处理状态或已关联的
 * 正式身份。</p>
 */
public interface BizPendingDeviceMapper extends BaseMapper<BizPendingDevice> {

    /**
     * 按身份组合唯一键记录一次未知设备发现。
     *
     * <p>冲突分支分别保留最早首次时间和最晚最近时间，并以最近时间判定最新样例；乱序到达
     * 只累加计数且不会覆盖较新的协议或样例。计数到达 BIGINT 上限后保持上限值。故意不更新 {@code status} 与
     * {@code bound_identity_id}，避免 IGNORED/BOUND 被后续上报意外恢复。</p>
     */
    @Insert("""
            INSERT INTO biz_pending_device (
                pending_id, identity_type, identity_value, profile_code, last_profile_version,
                first_seen_time, last_seen_time, report_count, latest_event_time, latest_time_source,
                latest_metrics_json, sample_truncated, status, bound_identity_id, create_time, update_time
            ) VALUES (
                #{pending.pendingId}, #{pending.identityType}, #{pending.identityValue},
                #{pending.profileCode}, #{pending.lastProfileVersion}, #{pending.firstSeenTime},
                #{pending.lastSeenTime}, 1, #{pending.latestEventTime}, #{pending.latestTimeSource},
                #{pending.latestMetricsJson}, #{pending.sampleTruncated}, 'DISCOVERED', NULL,
                CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
            )
            ON DUPLICATE KEY UPDATE
                first_seen_time = LEAST(first_seen_time, VALUES(first_seen_time)),
                profile_code = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(profile_code)
                    ELSE profile_code
                END,
                last_profile_version = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(last_profile_version)
                    ELSE last_profile_version
                END,
                last_seen_time = GREATEST(last_seen_time, VALUES(last_seen_time)),
                report_count = CASE
                    WHEN report_count < 9223372036854775807 THEN report_count + 1
                    ELSE report_count
                END,
                latest_event_time = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(latest_event_time)
                    ELSE latest_event_time
                END,
                latest_time_source = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(latest_time_source)
                    ELSE latest_time_source
                END,
                latest_metrics_json = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(latest_metrics_json)
                    ELSE latest_metrics_json
                END,
                sample_truncated = CASE
                    WHEN VALUES(last_seen_time) >= last_seen_time THEN VALUES(sample_truncated)
                    ELSE sample_truncated
                END,
                update_time = CURRENT_TIMESTAMP(3)
            """)
    int upsertDiscovery(@Param("pending") BizPendingDevice pending);

    /**
     * 以状态、截止时间和调用方上限选出一批可清理 ID。
     *
     * <p>BOUND 记录从不在清理候选中；调用方必须传入正数上限，随后仍需使用带同一截止条件的
     * 删除方法，以避免读取后新上报的记录被误删。</p>
     */
    @Select("""
            SELECT pending_id
            FROM biz_pending_device
            WHERE status IN ('DISCOVERED', 'IGNORED')
              AND last_seen_time < #{cutoff}
            ORDER BY last_seen_time, pending_id
            LIMIT #{limit}
            """)
    List<String> selectExpiredEligibleIds(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /**
     * 删除前重新核验状态和截止时间，避免选择与删除之间把新上报或已绑定记录清除。
     */
    @Delete("""
            <script>
            DELETE FROM biz_pending_device
            WHERE
            <choose>
              <when test="pendingIds != null and !pendingIds.isEmpty()">
                pending_id IN
                <foreach collection="pendingIds" item="pendingId" open="(" separator="," close=")">
                  #{pendingId}
                </foreach>
              </when>
              <otherwise>
                1 = 0
              </otherwise>
            </choose>
              AND status IN ('DISCOVERED', 'IGNORED')
              AND last_seen_time &lt; #{cutoff}
            </script>
            """)
    int deleteExpiredEligibleByIds(
            @Param("pendingIds") Collection<String> pendingIds,
            @Param("cutoff") LocalDateTime cutoff);
}
