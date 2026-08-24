package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicyVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
/** 草稿、正式版本及其发布生命周期的 MySQL 访问入口。 */
public interface BizQualityUsagePolicyVersionMapper extends BaseMapper<BizQualityUsagePolicyVersion> {
    @Select("SELECT * FROM biz_quality_usage_policy_version WHERE version_id=#{versionId} FOR UPDATE")
    BizQualityUsagePolicyVersion selectByIdForUpdate(@Param("versionId") String versionId);

    @Select("""
            SELECT * FROM biz_quality_usage_policy_version
            WHERE change_set_id=#{changeSetId}
            ORDER BY policy_id, version_no
            """)
    List<BizQualityUsagePolicyVersion> selectByChangeSetId(@Param("changeSetId") String changeSetId);

    @Select("""
            SELECT * FROM biz_quality_usage_policy_version
            WHERE policy_id=#{policyId}
            ORDER BY version_no
            """)
    List<BizQualityUsagePolicyVersion> selectByPolicyId(@Param("policyId") String policyId);

    @Select("SELECT COALESCE(MAX(version_no),0) FROM biz_quality_usage_policy_version WHERE policy_id=#{policyId}")
    int selectMaxVersionNo(@Param("policyId") String policyId);

    @Update("""
            UPDATE biz_quality_usage_policy_version
            SET copied_from_version_id=#{copiedFromVersionId}, change_source=#{changeSource},
                change_reason=#{changeReason}, update_time=#{updateTime}
            WHERE version_id=#{versionId} AND status='DRAFT'
            """)
    int updateDraftMetadata(@Param("versionId") String versionId,
                            @Param("copiedFromVersionId") String copiedFromVersionId,
                            @Param("changeSource") String changeSource,
                            @Param("changeReason") String changeReason,
                            @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE biz_quality_usage_policy_version
            SET status='ACTIVE', effective_from_ms=#{effectiveFromMs}, effective_to_ms=NULL,
                published_config_revision=#{configRevision}, published_by=#{publishedBy},
                published_at=#{publishedAt}, update_time=#{publishedAt}
            WHERE version_id=#{versionId} AND status='DRAFT'
            """)
    int activate(@Param("versionId") String versionId,
                 @Param("effectiveFromMs") long effectiveFromMs,
                 @Param("configRevision") long configRevision,
                 @Param("publishedBy") Long publishedBy,
                 @Param("publishedAt") LocalDateTime publishedAt);

    @Update("""
            UPDATE biz_quality_usage_policy_version
            SET status='RETIRED', effective_to_ms=#{effectiveToMs}, retired_at=#{retiredAt},
                update_time=#{retiredAt}
            WHERE version_id=#{versionId} AND status='ACTIVE'
            """)
    int retire(@Param("versionId") String versionId,
               @Param("effectiveToMs") long effectiveToMs,
               @Param("retiredAt") LocalDateTime retiredAt);

    @Delete("DELETE FROM biz_quality_usage_policy_version WHERE change_set_id=#{changeSetId} AND status='DRAFT'")
    int deleteDraftsByChangeSet(@Param("changeSetId") String changeSetId);
}
