package com.platform.iot.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.collection.model.entity.BizCollectionPolicyVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/** 采集策略草稿与不可变发布版本的 MySQL 持久化入口。 */
public interface BizCollectionPolicyVersionMapper extends BaseMapper<BizCollectionPolicyVersion> {
    @Select("SELECT * FROM biz_collection_policy_version WHERE version_id=#{versionId} FOR UPDATE")
    BizCollectionPolicyVersion selectByIdForUpdate(@Param("versionId") String versionId);

    @Select("SELECT COALESCE(MAX(version_no),0) FROM biz_collection_policy_version WHERE policy_id=#{policyId}")
    int selectMaxVersionNo(@Param("policyId") String policyId);

    @Update("""
            UPDATE biz_collection_policy_version
            SET enabled_flag=#{enabledFlag}, expected_interval_seconds=#{expectedIntervalSeconds},
                allowed_delay_seconds=#{allowedDelaySeconds}, raw_retention_mode=#{rawRetentionMode},
                raw_retention_days=#{rawRetentionDays}, minute_retention_mode=#{minuteRetentionMode},
                minute_retention_days=#{minuteRetentionDays}, change_reason=#{changeReason},
                revision=revision+1, update_time=CURRENT_TIMESTAMP(3)
            WHERE version_id=#{versionId} AND revision=#{expectedRevision} AND status='DRAFT'
            """)
    int updateDraft(@Param("versionId") String versionId,
                    @Param("enabledFlag") Integer enabledFlag,
                    @Param("expectedIntervalSeconds") Integer expectedIntervalSeconds,
                    @Param("allowedDelaySeconds") Integer allowedDelaySeconds,
                    @Param("rawRetentionMode") String rawRetentionMode,
                    @Param("rawRetentionDays") Integer rawRetentionDays,
                    @Param("minuteRetentionMode") String minuteRetentionMode,
                    @Param("minuteRetentionDays") Integer minuteRetentionDays,
                    @Param("changeReason") String changeReason,
                    @Param("expectedRevision") Integer expectedRevision);
}
