package com.platform.hvac.asset.service;

import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Objects;

/** 仅处理 D01 合成建筑的目录排除和保留引用归档；审核编排由敏感变更服务负责。 */
@Service
public class D01TestBuildingRetirementService {
    private final JdbcTemplate jdbc;
    private final AuditGovernanceProperties auditProperties;

    public D01TestBuildingRetirementService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                            AuditGovernanceProperties auditProperties) {
        this.jdbc = jdbc;
        this.auditProperties = auditProperties;
    }

    @Transactional
    public void exclude(String buildingId, String expectedName, String evidenceSha256, String requestId) {
        requireEligible(buildingId, expectedName);
        if (retirementStatus(buildingId, true) != null) throw conflict("测试建筑已排除或归档");
        jdbc.update("""
                INSERT INTO biz_test_building_retirement
                (building_id,status,evidence_sha256,exclusion_request_id,excluded_at)
                VALUES (?,'EXCLUDED',?,?,CURRENT_TIMESTAMP(3))
                """, buildingId, evidenceSha256, requestId);
    }

    @Transactional
    public void archive(String buildingId, String expectedName, String evidenceSha256, String requestId) {
        requireEligible(buildingId, expectedName);
        List<String> hashes = jdbc.query("""
                SELECT evidence_sha256 FROM biz_test_building_retirement
                WHERE building_id=? AND status='EXCLUDED' FOR UPDATE
                """, (row, index) -> row.getString(1), buildingId);
        if (hashes.size() != 1 || !hashes.getFirst().equals(evidenceSha256)) {
            throw conflict("测试建筑未完成同一证据快照的统计排除");
        }
        if (jdbc.update("""
                UPDATE biz_test_building_retirement SET status='ARCHIVED',
                archive_request_id=?,archived_at=CURRENT_TIMESTAMP(3)
                WHERE building_id=? AND status='EXCLUDED'
                """, requestId, buildingId) != 1) throw conflict("测试建筑归档状态已变化");
    }

    public String status(String buildingId) {
        String retired = retirementStatus(buildingId, false);
        return retired == null ? "ACTIVE" : retired;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNotRetired(String buildingId) {
        // 与排除/归档共用建筑行锁，防止审查接入状态后并发重新发布来源。
        jdbc.query("SELECT building_id FROM building WHERE building_id=? AND del_flag=0 FOR UPDATE",
                (row, index) -> row.getString(1), buildingId);
        if (retirementStatus(buildingId, true) != null) throw conflict("已排除或归档的测试建筑不可再修改");
    }

    private void requireEligible(String buildingId, String expectedName) {
        if (auditProperties.getEnvironmentMode() != AuditEnvironmentMode.TEST) {
            throw conflict("仅测试环境允许归档 D01 合成建筑");
        }
        List<String> names = jdbc.query("""
                SELECT building_name FROM building WHERE building_id=? AND del_flag=0 FOR UPDATE
                """, (row, index) -> row.getString(1), buildingId);
        // 名称和冻结命令同时校验，避免把真实建筑误当作可清理的 D01 测试资产。
        if (names.size() != 1 || !Objects.equals(names.getFirst(), expectedName)
                || !expectedName.startsWith("D01专用验收") || !expectedName.contains("合成数据")) {
            throw conflict("目标不是已确认的 D01 合成建筑");
        }
        List<String> sources = jdbc.query("SELECT status FROM biz_data_source WHERE building_id=? FOR UPDATE",
                (row, index) -> row.getString(1), buildingId);
        List<Integer> identities = jdbc.query("SELECT status FROM biz_device_identity WHERE building_id=? FOR UPDATE",
                (row, index) -> row.getObject(1, Integer.class), buildingId);
        if (sources.stream().anyMatch(status -> !"DISABLED".equals(status))
                || identities.stream().anyMatch(status -> !Integer.valueOf(0).equals(status))) {
            throw conflict("数据源或设备身份尚未全部停用");
        }
        if (count("SELECT COUNT(*) FROM biz_energy_period_result_current WHERE building_id=?", buildingId) != 0
                || count("SELECT COUNT(*) FROM biz_carbon_project_version WHERE building_id=?", buildingId) != 0) {
            throw conflict("正式能源或碳结果仍引用该建筑");
        }
    }

    private long count(String sql, String buildingId) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, buildingId));
    }

    private String retirementStatus(String buildingId, boolean lock) {
        List<String> values = jdbc.query("SELECT status FROM biz_test_building_retirement WHERE building_id=?"
                + (lock ? " FOR UPDATE" : ""), (row, index) -> row.getString(1), buildingId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, "D01_TEST_BUILDING_RETIREMENT_CONFLICT", message);
    }
}
