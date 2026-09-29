package com.platform.hvac.asset.service;

import com.platform.hvac.asset.api.MeterCoverageContracts.Target;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** MySQL 表计档案仓储；历史版本保存对象名称快照，不读取或修改时序数据。 */
@Repository
public class MeterCoverageRepository {
    private final JdbcTemplate jdbc;

    public MeterCoverageRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Equipment(String id, String buildingId, String code, String name,
                            String spaceId, String spaceName, boolean meter) {}
    public record Revision(String meterId, long revision, long effectiveAt,
                           String installationSpaceId, String installationSpaceName,
                           String label, String reason) {}

    private static final String METER_SQL = """
            (e.equip_category='ELECTRIC_METER' OR e.type_code IN ('ELECTRIC_METER_1P','ELECTRIC_METER_3P')
             OR COALESCE(e.product_id,'') IN ('PRODUCT_IDU_METER_1039','PRODUCT_ODU_METER_339'))
            """;
    private static final String EQUIPMENT_SQL = """
            SELECT e.equip_id,e.building_id,e.equip_code,e.equip_name,e.space_id,s.space_name,
            """ + " CASE WHEN " + METER_SQL + " THEN 1 ELSE 0 END AS meter FROM biz_equipment e "
            + "LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0 ";

    public Optional<Equipment> equipment(String id, boolean lock) {
        // 写入先锁表计，再按稳定顺序锁被测设备；锁定读取避免 MySQL 快照返回并发变更前的归属。
        if (lock) jdbc.queryForList("SELECT equip_id FROM biz_equipment WHERE equip_id=? FOR UPDATE", id);
        return jdbc.query(EQUIPMENT_SQL + " WHERE e.equip_id=? AND e.del_flag=0" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Equipment(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getString(6), r.getBoolean(7)), id)
                .stream().findFirst();
    }

    public Optional<String> installationSpace(String buildingId, String id) {
        return jdbc.query("SELECT space_name FROM biz_space WHERE building_id=? AND space_id=? AND del_flag=0 FOR UPDATE",
                (r, n) -> r.getString(1), buildingId, id).stream().findFirst();
    }

    public List<Equipment> candidates(String buildingId, String keyword, int offset, int size) {
        return jdbc.query(EQUIPMENT_SQL + candidateWhere() + " ORDER BY e.equip_code,e.equip_id LIMIT ? OFFSET ?",
                (r, n) -> new Equipment(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getString(6), false),
                buildingId, keyword, keyword, size, offset);
    }

    public long candidateCount(String buildingId, String keyword) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment e " + candidateWhere(),
                Long.class, buildingId, keyword, keyword);
    }

    private String candidateWhere() {
        return " WHERE e.building_id=? AND e.del_flag=0 AND NOT " + METER_SQL
                + " AND (LOWER(e.equip_name) LIKE ? ESCAPE '!' OR LOWER(e.equip_code) LIKE ? ESCAPE '!')";
    }

    public List<Revision> revisions(String meterId, int offset, int size) {
        return jdbc.query("""
                SELECT meter_id,revision,effective_at,installation_space_id,installation_space_name,scope_label,reason
                FROM biz_meter_coverage_revision WHERE meter_id=? ORDER BY revision DESC LIMIT ? OFFSET ?
                """, (r, n) -> new Revision(r.getString(1), r.getLong(2), r.getLong(3),
                r.getString(4), r.getString(5), r.getString(6), r.getString(7)), meterId, size, offset);
    }

    public long revisionCount(String meterId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_meter_coverage_revision WHERE meter_id=?", Long.class, meterId);
    }

    public List<Target> targets(String meterId, long revision) {
        return jdbc.query("""
                SELECT t.target_equipment_id,t.equipment_code,t.equipment_name,t.space_id,t.space_name,
                       CASE WHEN e.equip_id IS NOT NULL AND e.del_flag=0 AND e.building_id=t.building_id THEN 1 ELSE 0 END
                FROM biz_meter_coverage_target t LEFT JOIN biz_equipment e ON e.equip_id=t.target_equipment_id
                WHERE t.meter_id=? AND t.revision=? ORDER BY t.equipment_code,t.target_equipment_id
                """, (r, n) -> new Target(r.getString(1), r.getString(2), r.getString(3),
                r.getString(4), r.getString(5), r.getBoolean(6)), meterId, revision);
    }

    public void insert(Revision revision, String buildingId, long actor, List<Equipment> targets) {
        jdbc.update("""
                INSERT INTO biz_meter_coverage_revision
                (meter_id,revision,building_id,effective_at,installation_space_id,installation_space_name,scope_label,reason,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, revision.meterId(), revision.revision(), buildingId, revision.effectiveAt(),
                revision.installationSpaceId(), revision.installationSpaceName(), revision.label(), revision.reason(), actor);
        for (Equipment target : targets) {
            jdbc.update("""
                    INSERT INTO biz_meter_coverage_target
                    (meter_id,revision,building_id,target_equipment_id,equipment_code,equipment_name,space_id,space_name)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, revision.meterId(), revision.revision(), buildingId, target.id(), target.code(),
                    target.name(), target.spaceId(), target.spaceName());
        }
    }
}
