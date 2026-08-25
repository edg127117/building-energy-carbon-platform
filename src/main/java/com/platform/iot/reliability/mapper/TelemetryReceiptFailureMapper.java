package com.platform.iot.reliability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.reliability.model.TelemetryReceiptFailure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
/** V2 异常明细的有界 MySQL 入口。 */
public interface TelemetryReceiptFailureMapper extends BaseMapper<TelemetryReceiptFailure> {
    @Delete("DELETE FROM biz_telemetry_receipt_failure WHERE occurred_at < #{before} LIMIT #{limit}")
    int deleteExpired(@Param("before") java.time.LocalDateTime before,
                      @Param("limit") int limit);
}
