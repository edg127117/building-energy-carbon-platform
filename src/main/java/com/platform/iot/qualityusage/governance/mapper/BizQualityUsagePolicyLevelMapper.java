package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsagePolicyLevel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
/** 允许等级子表的显式读取与受控替换入口。 */
public interface BizQualityUsagePolicyLevelMapper extends BaseMapper<BizQualityUsagePolicyLevel> {
    @Select("""
            SELECT * FROM biz_quality_usage_policy_level
            WHERE version_id=#{versionId}
            ORDER BY quality_level
            """)
    List<BizQualityUsagePolicyLevel> selectByVersionId(@Param("versionId") String versionId);

    @Delete("DELETE FROM biz_quality_usage_policy_level WHERE version_id=#{versionId}")
    int deleteByVersionId(@Param("versionId") String versionId);
}
