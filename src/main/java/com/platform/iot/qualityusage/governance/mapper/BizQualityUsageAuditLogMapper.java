package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
/** 审计事实和 Idempotency-Key 重放检查的持久化入口。 */
public interface BizQualityUsageAuditLogMapper extends BaseMapper<BizQualityUsageAuditLog> {
    @Select("SELECT * FROM biz_quality_usage_audit_log WHERE idempotency_key=#{idempotencyKey}")
    BizQualityUsageAuditLog selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
