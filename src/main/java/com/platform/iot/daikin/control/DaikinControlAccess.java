package com.platform.iot.daikin.control;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditGovernanceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 从 MySQL 复核有效账号与正式身份；TEST 全开放免人员和设备许可，其他环境保留原授权边界。 */
@Service
public class DaikinControlAccess {
    private final JdbcTemplate jdbc;
    private final BackendDutyService duties;
    private final SysRoleMapper roles;
    private final SysMenuMapper menus;
    private final DaikinControlProperties properties;
    private final AuditGovernanceProperties audit;

    public DaikinControlAccess(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, BackendDutyService duties,
                               SysRoleMapper roles, SysMenuMapper menus, DaikinControlProperties properties) {
        this(jdbc, duties, roles, menus, properties, new AuditGovernanceProperties());
    }

    @Autowired
    public DaikinControlAccess(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, BackendDutyService duties,
                               SysRoleMapper roles, SysMenuMapper menus, DaikinControlProperties properties,
                               AuditGovernanceProperties audit) {
        this.jdbc = jdbc; this.duties = duties; this.roles = roles; this.menus = menus; this.properties = properties;
        this.audit = audit;
    }

    public boolean testOpenControls() {
        return properties.isTestOpenControls() && audit.getEnvironmentMode() == AuditEnvironmentMode.TEST;
    }

    public void requireAccount(long user) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND del_flag=0", Integer.class, user) != 1)
            throw error(403, "FORBIDDEN");
    }

    public String requireRead(long user, String equipmentId) {
        if (testOpenControls()) {
            requireAccount(user);
            var values = jdbc.queryForList("SELECT building_id FROM biz_equipment WHERE equip_id=? AND del_flag=0", String.class, equipmentId);
            if (values.size() != 1) throw error(409, "TARGET_UNAVAILABLE");
            return values.getFirst();
        }
        var currentRoles = Set.copyOf(roles.selectRoleKeysByUserId(user));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND del_flag=0",
                Integer.class, user) != 1 || currentRoles.isEmpty() || currentRoles.contains("THIRD_PARTY")) throw error(403, "FORBIDDEN");
        boolean admin = currentRoles.contains("PLATFORM_ADMIN");
        if (!admin && menus.selectVisibleMenusByUserId(user).stream().noneMatch(menu -> "C".equals(menu.getMenuType())
                && Set.of("/operations/realtime/hvac", "/operations/overview/running").contains(menu.getPath()))) throw error(403, "FORBIDDEN");
        var buildings = jdbc.queryForList("SELECT building_id FROM biz_equipment WHERE equip_id=? AND del_flag=0", String.class, equipmentId);
        if (buildings.size() != 1 || !admin && jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_building WHERE user_id=? AND building_id=?", Integer.class, user, buildings.getFirst()) == 0)
            throw error(403, "FORBIDDEN");
        return buildings.getFirst();
    }

    public void requireControl(long user, String equipmentId) {
        requireRead(user, equipmentId);
        if (testOpenControls()) return;
        if (properties.isTestAccessEnabled() && !properties.getTestEquipmentIds().isEmpty()
                && !properties.getTestEquipmentIds().contains(equipmentId)) throw error(403, "FORBIDDEN");
        var current = roles.selectRoleKeysByUserId(user);
        if (current.stream().noneMatch(Set.of("PLATFORM_ADMIN", "ENERGY_MANAGER")::contains)
                || !(properties.isTestAccessEnabled() && current.contains("PLATFORM_ADMIN"))
                && !duties.hasDuty(user, BackendDuty.HVAC_DEVICE_CONTROL)) throw error(403, "FORBIDDEN");
    }

    public Target target(String equipmentId) {
        if (testOpenControls()) return identity(resolve(equipmentId, false));
        return resolve(equipmentId, true);
    }

    /** 已发送指令按持久化策略核验，启用 TEST 简化模式不能替换旧指令的许可版本。 */
    public Target legacyTarget(String equipmentId) {
        return resolve(equipmentId, true);
    }

    /** 批量开放只解析正式身份，未开放设备也可进入核验；不据此授予实际控制权限。 */
    public Target candidate(String equipmentId) {
        Target t = resolve(equipmentId, false);
        return identity(t);
    }

    /** 恢复只比较正式归属；撤销动作许可不能妨碍管理员只读核验旧异常，也不会重新授予许可。 */
    static Target identity(Target t) {
        return new Target(t.equipmentId(), t.buildingId(), t.identityId(), t.sourceId(), t.siteId(), t.controllerId(),
                t.unitId(), t.vendorId(), t.pendingId(), t.mappingVersion(), 0, false, null, null, null);
    }

    public boolean canManageGrants(long user) {
        if (testOpenControls()) {
            try { requireAccount(user); return true; }
            catch (com.platform.framework.exception.BusinessException ex) { return false; }
        }
        var current = Set.copyOf(roles.selectRoleKeysByUserId(user));
        return current.contains("PLATFORM_ADMIN") && !current.contains("THIRD_PARTY")
                && jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND del_flag=0", Integer.class, user) == 1;
    }

    public void requireGrantAdmin(long user) {
        if (!canManageGrants(user)) throw error(403, "FORBIDDEN");
    }

    private Target resolve(String equipmentId, boolean requireGrant) {
        var rows = jdbc.query("""
                SELECT e.equip_id,e.building_id,i.identity_id,d.source_id,d.site_id,d.controller_id,
                       d.unit_id,d.equipment_id AS vendor_id,d.pending_id,m.mapping_version,g.version AS grant_version,
                       g.air_flow_confirmed,g.relationship_hash,g.approved_actions,g.evidence
                FROM biz_equipment e
                JOIN biz_device_identity i ON i.equip_id=e.equip_id AND i.building_id=e.building_id
                     AND i.identity_type='DAIKIN_UNIT' AND i.status=1
                JOIN biz_pending_device p ON p.bound_identity_id=i.identity_id AND p.status='BOUND'
                JOIN biz_daikin_directory d ON d.pending_id=p.pending_id AND d.device_kind='INDOOR' AND d.missing=0
                JOIN biz_device_product product ON product.product_id=e.product_id AND product.status='ENABLED'
                     AND product.identity_type=i.identity_type AND product.expected_profile_code=i.expected_profile_code
                JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id AND m.building_id=e.building_id
                %s biz_daikin_control_grant g ON g.equipment_id=e.equip_id AND g.identity_id=i.identity_id
                     AND g.source_id=d.source_id AND g.vendor_id=d.equipment_id AND g.building_id=e.building_id
                     AND g.mapping_version=m.mapping_version AND g.enabled=1
                WHERE e.equip_id=? AND e.del_flag=0 LIMIT 2
                """.formatted(requireGrant ? "JOIN" : "LEFT JOIN"), (rs, n) -> new Target(rs.getString("equip_id"), rs.getString("building_id"), rs.getString("identity_id"),
                rs.getString("source_id"), rs.getString("site_id"), rs.getString("controller_id"), rs.getString("unit_id"),
                rs.getString("vendor_id"), rs.getString("pending_id"), rs.getInt("mapping_version"), rs.getLong("grant_version"),
                rs.getBoolean("air_flow_confirmed"), rs.getString("relationship_hash"), rs.getString("approved_actions"),
                rs.getString("evidence")), equipmentId);
        if (rows.size() != 1) throw error(409, "TARGET_UNAVAILABLE");
        Target target = rows.getFirst();
        // 一个厂家 URL 不能对应多条目录身份；否则单台操作可能落到另一个项目或控制器。
        if (target.vendorId() == null || target.vendorId().isBlank() || jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_daikin_directory WHERE source_id=? AND equipment_id=?", Integer.class,
                target.sourceId(), target.vendorId()) != 1) throw error(409, "IDENTITY_CONFLICT");
        return target;
    }

    public record Target(String equipmentId, String buildingId, String identityId, String sourceId,
                         String siteId, String controllerId, String unitId, String vendorId, String pendingId,
                         int mappingVersion, long grantVersion, boolean airFlowConfirmed, String relationshipHash,
                         String approvedActions, String evidence) {
        public String fingerprint() {
            StringBuilder value = new StringBuilder();
            for (String part : java.util.List.of(equipmentId, buildingId, identityId, sourceId, siteId, controllerId,
                    unitId, vendorId, pendingId, Integer.toString(mappingVersion), Long.toString(grantVersion), Boolean.toString(airFlowConfirmed),
                    java.util.Objects.toString(relationshipHash, ""), java.util.Objects.toString(approvedActions, ""), java.util.Objects.toString(evidence, "")))
                value.append(part.length()).append(':').append(part);
            return hash(value.toString());
        }
        public String physicalKey() { return hash(sourceId.length() + ":" + sourceId + vendorId.length() + ":" + vendorId); }
        public String controllerKey() { return hash(sourceId.length() + ":" + sourceId + controllerId.length() + ":" + controllerId); }
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
