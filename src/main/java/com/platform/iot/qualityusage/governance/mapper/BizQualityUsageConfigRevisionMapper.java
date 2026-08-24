package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageConfigRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
/** 质量使用配置全局单调修订号的串行化访问入口。 */
public interface BizQualityUsageConfigRevisionMapper extends BaseMapper<BizQualityUsageConfigRevision> {
    @Select("SELECT * FROM biz_quality_usage_config_revision WHERE singleton_id=1 FOR UPDATE")
    BizQualityUsageConfigRevision selectForUpdate();

    @Update("""
            UPDATE biz_quality_usage_config_revision
            SET config_revision=#{configRevision}, last_change_summary=#{summary}, updated_at=#{updatedAt}
            WHERE singleton_id=1
            """)
    int updateRevision(@Param("configRevision") long configRevision,
                       @Param("summary") String summary,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** MySQL 与 H2 MySQL 模式均支持该函数，保证生效分钟来自事务所见数据库时钟。 */
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) AS BIGINT) * 1000")
    Long selectDatabaseEpochMs();
}
