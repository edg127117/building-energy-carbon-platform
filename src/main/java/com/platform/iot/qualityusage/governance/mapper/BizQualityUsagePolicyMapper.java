package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
/** 稳定策略身份、活动指针与待审核占位的锁定读写入口。 */
public interface BizQualityUsagePolicyMapper extends BaseMapper<BizQualityUsagePolicy> {
    @Select("SELECT * FROM biz_quality_usage_policy WHERE policy_id=#{policyId} FOR UPDATE")
    BizQualityUsagePolicy selectByIdForUpdate(@Param("policyId") String policyId);

    @Select("""
            SELECT * FROM biz_quality_usage_policy
            WHERE point_id=#{pointId} AND scenario_id=#{scenarioId}
            """)
    BizQualityUsagePolicy selectByPointAndScenario(
            @Param("pointId") String pointId, @Param("scenarioId") String scenarioId);

    @Select("""
            SELECT * FROM biz_quality_usage_policy
            WHERE point_id=#{pointId} AND scenario_id=#{scenarioId}
            FOR UPDATE
            """)
    BizQualityUsagePolicy selectByPointAndScenarioForUpdate(
            @Param("pointId") String pointId, @Param("scenarioId") String scenarioId);

    @Update("""
            UPDATE biz_quality_usage_policy
            SET current_active_version_id=#{activeVersionId}, pending_review_request_id=#{pendingReviewRequestId},
                update_time=#{updateTime}
            WHERE policy_id=#{policyId}
            """)
    int updatePointers(@Param("policyId") String policyId,
                       @Param("activeVersionId") String activeVersionId,
                       @Param("pendingReviewRequestId") String pendingReviewRequestId,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE biz_quality_usage_policy
            SET pending_review_request_id=NULL, update_time=#{updateTime}
            WHERE pending_review_request_id=#{requestId}
            """)
    int clearPendingByRequest(@Param("requestId") String requestId,
                              @Param("updateTime") LocalDateTime updateTime);
}
