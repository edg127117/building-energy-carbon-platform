package com.platform.carbon;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.platform.framework.exception.BusinessException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 只读取有界版本头；正式批次证据在完成后不可变，展示缓存不重复装载逐测点冻结JSON。 */
@Repository
class CarbonDisplaySourceVersion {
    private final JdbcTemplate jdbc;
    private final CarbonDisplayQueryProperties properties;
    CarbonDisplaySourceVersion(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,CarbonDisplayQueryProperties properties){this.jdbc=jdbc;this.properties=properties;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String version(CarbonDisplayQueryContracts.Query q){
        var parts=new ArrayList<Object>();parts.add(List.of("CARBON_DISPLAY_ALGORITHM_1",q.key()));
        ZoneId zone=ZoneId.of(q.timezoneId());
        // YTD原服务的证据指纹包括去年同期，故其版本头也必须覆盖；不改变原计算和比较口径。
        LocalDate start=LocalDate.of(q.year()-("MONTHLY_YTD".equals(q.mode())?1:0),1,1);
        LocalDate end=LocalDate.of(q.year()+1,1,1);
        var from=Timestamp.from(start.atStartOfDay(zone).toInstant());var to=Timestamp.from(end.atStartOfDay(zone).toInstant());
        add(parts,"""
                SELECT calculation_batch_id,period_type,period_start,period_end,timezone_id,result_nature,publication_status,status,
                rounding_policy_version_id,completed_at,detail_count FROM biz_carbon_calculation_batch
                WHERE building_id=? AND period_start>=? AND period_start<? ORDER BY calculation_batch_id
                """,q.buildingId(),from,to);
        add(parts,"""
                SELECT r.old_calculation_batch_id,r.new_calculation_batch_id,r.relation_status FROM biz_carbon_result_relation r
                JOIN biz_carbon_calculation_batch b ON b.calculation_batch_id=r.old_calculation_batch_id
                WHERE b.building_id=? AND b.period_start>=? AND b.period_start<? ORDER BY r.old_calculation_batch_id,r.new_calculation_batch_id
                """,q.buildingId(),from,to);
        add(parts,"""
                SELECT s.calculation_batch_id,s.metric_code,s.dimension_code,s.evidence_hash FROM biz_carbon_calculation_summary s
                JOIN biz_carbon_calculation_batch b ON b.calculation_batch_id=s.calculation_batch_id
                WHERE b.building_id=? AND b.period_start>=? AND b.period_start<? ORDER BY s.calculation_batch_id,s.metric_code,s.dimension_code
                """,q.buildingId(),from,to);
        if("JOINT_FLOW".equals(q.kind())) {
            if(q.configurationVersionId()==null) add(parts,"SELECT version_id,revision FROM biz_joint_flow_selection WHERE building_id=? ORDER BY building_id",q.buildingId());
            add(parts,"""
                    SELECT version_id,revision,status,configuration_hash FROM biz_joint_flow_configuration WHERE building_id=?
                    AND version_id=COALESCE(?,(SELECT version_id FROM biz_joint_flow_selection WHERE building_id=?)) ORDER BY version_id
                    """,q.buildingId(),q.configurationVersionId(),q.buildingId());
            // options来自当前参数目录，并不完全由原resultVersion覆盖；连同规则状态/适用期独立纳入失效依据。
            for(String table:List.of("biz_energy_conversion_parameter_version","biz_energy_conversion_formula_version","biz_standard_coal_lhv_version",
                    "biz_energy_item_version","biz_measurement_unit_version","biz_energy_item_unit_compatibility_version"))
                add(parts,"SELECT version_id,config_revision,status,effective_from,effective_to FROM "+table+" ORDER BY version_id");
        } else {
            add(parts,"SELECT building_id,region_code,building_type,carbon_evaluation_category,carbon_evaluation_subcategory,del_flag FROM building WHERE building_id=? ORDER BY building_id",q.buildingId());
            add(parts,"SELECT catalog_id,version_id,revision_no,evidence_hash FROM biz_carbon_intensity_standard_version ORDER BY catalog_id,revision_no");
            add(parts,"""
                    SELECT v.denominator_version_id,v.config_revision,v.status,v.effective_from,v.effective_to FROM biz_carbon_denominator_version v
                    JOIN biz_carbon_denominator d ON d.denominator_id=v.denominator_id WHERE d.building_id=? ORDER BY v.denominator_version_id
                    """,q.buildingId());
            add(parts,"""
                    SELECT a.applicability_id,a.denominator_version_id,a.effective_from,a.effective_to FROM biz_carbon_area_applicability a
                    JOIN biz_carbon_denominator_version v ON v.denominator_version_id=a.denominator_version_id
                    JOIN biz_carbon_denominator d ON d.denominator_id=v.denominator_id WHERE d.building_id=? ORDER BY a.applicability_id
                    """,q.buildingId());
            if("MONTHLY_YTD".equals(q.mode())) add(parts,"SELECT record_id,status,revision,month_date FROM biz_energy_reporting_record WHERE building_id=? AND record_kind='BATCH' AND month_date>=? AND month_date<? ORDER BY record_id",q.buildingId(),start.toString(),end.toString());
        }
        return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(parts)));
    }
    private void add(List<Object> parts,String sql,Object... args){
        var parameters=new ArrayList<Object>(Arrays.asList(args));parameters.add(properties.getMaximumVersionRows()+1);
        var rows=jdbc.queryForList(sql+" LIMIT ?",parameters.toArray());
        if(rows.size()>properties.getMaximumVersionRows())throw new BusinessException(503,"CARBON_DISPLAY_VERSION_CAPACITY","能碳展示来源版本超出读取容量");
        parts.add(rows.stream().map(r->r.values().stream().map(v->Objects.toString(v,"")).toList()).toList());
    }
}
