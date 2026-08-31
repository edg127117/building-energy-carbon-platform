package com.platform.energy.activity;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Repository
/**
 * 组合 MySQL 测点档案与能源专业属性，供活动数据读取执行输入门禁。
 *
 * <p>使用左连接保留“测点存在但能源属性缺失”的事实，使 Service 能安全拒绝而不是把
 * 普通 HVAC 测点自动解释成某种能源。该目录不读取 TDengine，也不修改专业配置。</p>
 */
public class EnergyActivityPointCatalog {
    private final JdbcTemplate jdbc;

    public EnergyActivityPointCatalog(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PointProfile> find(String buildingId, Collection<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                Collections.nCopies(pointIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(buildingId);
        args.addAll(pointIds);
        String sql = """
                SELECT p.point_id,p.point_code,p.unit,
                       e.profile_id,e.energy_type,e.energy_subtype,e.value_semantics,
                       e.confirmation_status,e.config_revision
                FROM biz_data_point p
                LEFT JOIN biz_energy_point_profile e
                  ON e.point_id=p.point_id AND e.building_id=p.building_id
                WHERE p.building_id=? AND p.del_flag=0
                  AND p.point_id IN (%s)
                ORDER BY p.point_id
                """.formatted(placeholders);
        return jdbc.query(sql, (rs, row) -> new PointProfile(
                rs.getString("point_id"),
                rs.getString("point_code"),
                rs.getString("unit"),
                rs.getString("profile_id"),
                rs.getString("energy_type"),
                rs.getString("energy_subtype"),
                rs.getString("value_semantics"),
                rs.getString("confirmation_status"),
                rs.getObject("config_revision") == null
                        ? null : rs.getInt("config_revision")), args.toArray());
    }

    public record PointProfile(
            String pointId,
            String pointCode,
            String unit,
            String profileId,
            String energyType,
            String energySubtype,
            String valueSemantics,
            String confirmationStatus,
            Integer profileRevision) {
    }
}
