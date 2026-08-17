package com.platform.iot.onboarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.onboarding.model.entity.BizOnboardingAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 设备接入审计的 MySQL 写入入口。 */
public interface BizOnboardingAuditLogMapper extends BaseMapper<BizOnboardingAuditLog> {
}
