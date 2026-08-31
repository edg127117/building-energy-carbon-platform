package com.platform.iot.energymetadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.energymetadata.model.entity.BizEnergyPointProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/** 能源测点属性及其乐观锁修订的 MySQL 持久化入口。 */
public interface BizEnergyPointProfileMapper extends BaseMapper<BizEnergyPointProfile> {
    @Select("SELECT * FROM biz_energy_point_profile WHERE profile_id=#{profileId} FOR UPDATE")
    BizEnergyPointProfile selectByIdForUpdate(@Param("profileId") String profileId);

    @Update("""
            UPDATE biz_energy_point_profile
            SET energy_type=#{energyType}, energy_subtype=#{energySubtype},
                value_semantics=#{valueSemantics}, reporting_period=#{reportingPeriod},
                annual_summary=#{annualSummary}, confirmation_status=#{confirmationStatus},
                evidence_reference=#{evidenceReference}, config_revision=config_revision+1,
                update_by=#{operatorId}, update_time=CURRENT_TIMESTAMP(3)
            WHERE profile_id=#{profileId} AND config_revision=#{expectedRevision}
            """)
    int updateByRevision(@Param("profileId") String profileId,
                         @Param("energyType") String energyType,
                         @Param("energySubtype") String energySubtype,
                         @Param("valueSemantics") String valueSemantics,
                         @Param("reportingPeriod") String reportingPeriod,
                         @Param("annualSummary") Boolean annualSummary,
                         @Param("confirmationStatus") String confirmationStatus,
                         @Param("evidenceReference") String evidenceReference,
                         @Param("operatorId") Long operatorId,
                         @Param("expectedRevision") Integer expectedRevision);
}
