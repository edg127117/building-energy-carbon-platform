package com.platform.iot.daikin.onboarding;

import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 将人工核对的位置附加到已同步的待接入内机，不修改厂家身份或正式设备归属。 */
@Service
public class DaikinPendingLocationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DaikinPendingLocationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                        TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public void mapIndoor(String sourceId, String siteId, List<LocationInput> inputs,
                          Long operatorId, Set<String> roles) {
        if (roles == null || !roles.contains("PLATFORM_ADMIN") || operatorId == null) throw forbidden();
        String source = text(sourceId, 200);
        String site = text(siteId, 200);
        if (inputs == null || inputs.isEmpty() || inputs.size() > 100) throw invalid("位置映射每批应为1至100台");
        Set<String> units = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        Set<String> assets = new HashSet<>();
        List<LocationInput> valid = inputs.stream().map(input -> {
            if (input == null) throw invalid("位置映射条目不能为空");
            var item = new LocationInput(text(input.unitId(), 200), text(input.apiId(), 200),
                    text(input.roomCode(), 50), text(input.monitorAddress(), 50),
                    text(input.assetReferenceCode(), 50));
            if (!units.add(item.unitId()) || !addresses.add(item.monitorAddress())
                    || !assets.add(item.assetReferenceCode())) throw invalid("本批内机、监控地址或资产参考编号重复");
            return item;
        }).toList();
        transaction.executeWithoutResult(status -> {
            // 与目录同步、项目改归属共用来源行锁，防止核对期间设备身份或建筑发生变化。
            if (jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                    String.class, source).size() != 1) throw invalid("来源不存在");
            List<String> buildings = jdbc.queryForList("""
                    SELECT building_id FROM biz_daikin_project_mapping WHERE source_id=? AND site_id=?
                    """, String.class, source, site);
            if (buildings.size() != 1) throw invalid("厂家项目尚未映射建筑");
            String building = buildings.getFirst();
            for (LocationInput input : valid) mapOne(source, site, building, input, operatorId);
        });
    }

    private void mapOne(String source, String site, String building, LocationInput input, Long operatorId) {
        List<String> pendingIds = jdbc.queryForList("""
                SELECT d.pending_id FROM biz_daikin_directory d
                JOIN biz_pending_device p ON p.pending_id=d.pending_id
                WHERE d.source_id=? AND d.site_id=? AND d.device_kind='INDOOR'
                  AND d.unit_id=? AND d.equipment_id=? AND d.missing=0 AND p.status='DISCOVERED'
                FOR UPDATE
                """, String.class, source, site, input.unitId(), input.apiId());
        if (pendingIds.size() != 1) throw invalid("内机身份不存在、已缺失或已正式绑定");
        List<String> rooms = jdbc.queryForList("""
                SELECT space_id FROM biz_space WHERE building_id=? AND space_code=?
                  AND space_type='ROOM' AND del_flag=0
                """, String.class, building, input.roomCode());
        if (rooms.size() != 1) throw invalid("房间不属于当前建筑");
        String pendingId = pendingIds.getFirst();
        List<Map<String, Object>> prior = jdbc.queryForList("""
                SELECT room_space_id,monitor_address,asset_reference_code
                FROM biz_daikin_pending_location WHERE pending_id=? FOR UPDATE
                """, pendingId);
        if (!prior.isEmpty()) {
            Map<String, Object> existing = prior.getFirst();
            if (!rooms.getFirst().equals(existing.get("room_space_id"))
                    || !input.monitorAddress().equals(existing.get("monitor_address"))
                    || !input.assetReferenceCode().equals(existing.get("asset_reference_code"))) {
                throw conflict("已有不同的位置映射，不能静默覆盖");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO biz_daikin_pending_location
                    (pending_id,room_space_id,monitor_address,asset_reference_code,mapped_by,mapped_at)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(3))
                """, pendingId, rooms.getFirst(), input.monitorAddress(), input.assetReferenceCode(), operatorId);
    }

    public DaikinPendingLocationView forPending(String pendingId) {
        return forPendingIds(List.of(pendingId)).get(pendingId);
    }

    public Map<String, DaikinPendingLocationView> forPendingIds(List<String> pendingIds) {
        if (pendingIds == null || pendingIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(pendingIds.size(), "?"));
        return jdbc.query("""
                SELECT l.pending_id,l.room_space_id,s.space_code,l.monitor_address,l.asset_reference_code
                FROM biz_daikin_pending_location l
                JOIN biz_daikin_directory d ON d.pending_id=l.pending_id
                JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id
                JOIN biz_space s ON s.space_id=l.room_space_id AND s.building_id=m.building_id
                    AND s.space_type='ROOM' AND s.del_flag=0
                WHERE l.pending_id IN (%s)
                """.formatted(placeholders), (rs, row) -> Map.entry(rs.getString(1),
                new DaikinPendingLocationView(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))),
                pendingIds.toArray()).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid("位置映射字段无效");
        return value.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, "DAIKIN_LOCATION_INVALID", message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, "DAIKIN_LOCATION_CONFLICT", message);
    }

    private static BusinessException forbidden() {
        return new BusinessException(403, "DAIKIN_LOCATION_FORBIDDEN", "仅平台管理员可登记位置映射");
    }

    public record LocationInput(String unitId, String apiId, String roomCode,
                                String monitorAddress, String assetReferenceCode) {
    }
}
