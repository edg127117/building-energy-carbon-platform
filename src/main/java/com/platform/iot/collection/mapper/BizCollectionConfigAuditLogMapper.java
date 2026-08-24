package com.platform.iot.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.collection.model.entity.BizCollectionConfigAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 采集治理脱敏审计的 MySQL 持久化入口。 */
public interface BizCollectionConfigAuditLogMapper extends BaseMapper<BizCollectionConfigAuditLog> {
}
