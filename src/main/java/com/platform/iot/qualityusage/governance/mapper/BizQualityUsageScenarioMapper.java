package com.platform.iot.qualityusage.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.qualityusage.governance.model.entity.BizQualityUsageScenario;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
/** 质量使用场景目录的持久化入口；写入只由增量迁移或受控运维流程承担。 */
public interface BizQualityUsageScenarioMapper extends BaseMapper<BizQualityUsageScenario> {
    @Select("SELECT * FROM biz_quality_usage_scenario WHERE scenario_code=#{scenarioCode} FOR UPDATE")
    BizQualityUsageScenario selectByCodeForUpdate(@Param("scenarioCode") String scenarioCode);

    @Select("SELECT * FROM biz_quality_usage_scenario WHERE scenario_code=#{scenarioCode}")
    BizQualityUsageScenario selectByCode(@Param("scenarioCode") String scenarioCode);
}
