package com.platform.iot.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.collection.model.entity.BizCollectionReviewRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
/** 采集配置审核申请及并发锁定查询的 MySQL 持久化入口。 */
public interface BizCollectionReviewRequestMapper extends BaseMapper<BizCollectionReviewRequest> {
    @Select("SELECT * FROM biz_collection_review_request WHERE request_id=#{requestId} FOR UPDATE")
    BizCollectionReviewRequest selectByIdForUpdate(@Param("requestId") String requestId);

    @Select("""
            SELECT * FROM biz_collection_review_request
            WHERE target_type=#{targetType} AND target_id=#{targetId} AND status='PENDING'
            LIMIT 1 FOR UPDATE
            """)
    BizCollectionReviewRequest selectPendingForUpdate(
            @Param("targetType") String targetType, @Param("targetId") String targetId);
}
