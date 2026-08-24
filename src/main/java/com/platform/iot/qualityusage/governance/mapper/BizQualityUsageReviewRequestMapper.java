package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageReviewRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
/** 审核申请及其不可变提交快照的锁定查询入口。 */
public interface BizQualityUsageReviewRequestMapper extends BaseMapper<BizQualityUsageReviewRequest> {
    @Select("SELECT * FROM biz_quality_usage_review_request WHERE request_id=#{requestId} FOR UPDATE")
    BizQualityUsageReviewRequest selectByIdForUpdate(@Param("requestId") String requestId);

    @Select("""
            SELECT * FROM biz_quality_usage_review_request
            WHERE change_set_id=#{changeSetId} AND status='PENDING'
            LIMIT 1 FOR UPDATE
            """)
    BizQualityUsageReviewRequest selectPendingByChangeSetForUpdate(@Param("changeSetId") String changeSetId);

    @Select("""
            SELECT * FROM biz_quality_usage_review_request
            WHERE change_set_id=#{changeSetId}
            ORDER BY request_no DESC
            """)
    List<BizQualityUsageReviewRequest> selectByChangeSetId(@Param("changeSetId") String changeSetId);

    @Select("SELECT COALESCE(MAX(request_no),0) FROM biz_quality_usage_review_request WHERE change_set_id=#{changeSetId}")
    int selectMaxRequestNo(@Param("changeSetId") String changeSetId);
}
