package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageChangeSet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
/** 单建筑变更集的乐观编辑与悲观状态转换入口。 */
public interface BizQualityUsageChangeSetMapper extends BaseMapper<BizQualityUsageChangeSet> {
    @Select("SELECT * FROM biz_quality_usage_change_set WHERE change_set_id=#{changeSetId} FOR UPDATE")
    BizQualityUsageChangeSet selectByIdForUpdate(@Param("changeSetId") String changeSetId);

    @Update("""
            UPDATE biz_quality_usage_change_set
            SET title=#{title}, description=#{description}, revision=revision+1, update_time=#{updateTime}
            WHERE change_set_id=#{changeSetId} AND status='DRAFT' AND revision=#{expectedRevision}
            """)
    int updateDraft(@Param("changeSetId") String changeSetId,
                    @Param("title") String title,
                    @Param("description") String description,
                    @Param("expectedRevision") int expectedRevision,
                    @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE biz_quality_usage_change_set
            SET status=#{status}, submitted_revision=#{submittedRevision}, has_been_submitted=#{hasBeenSubmitted},
                submitted_at=#{submittedAt}, published_at=#{publishedAt}, cancelled_at=#{cancelledAt},
                last_failure_code=#{lastFailureCode}, update_time=#{updateTime}
            WHERE change_set_id=#{changeSetId}
            """)
    int updateLifecycle(@Param("changeSetId") String changeSetId,
                        @Param("status") String status,
                        @Param("submittedRevision") Integer submittedRevision,
                        @Param("hasBeenSubmitted") int hasBeenSubmitted,
                        @Param("submittedAt") LocalDateTime submittedAt,
                        @Param("publishedAt") LocalDateTime publishedAt,
                        @Param("cancelledAt") LocalDateTime cancelledAt,
                        @Param("lastFailureCode") String lastFailureCode,
                        @Param("updateTime") LocalDateTime updateTime);
}
