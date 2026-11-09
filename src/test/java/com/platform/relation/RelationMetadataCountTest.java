package com.platform.relation;

import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import com.platform.relation.RelationGovernanceRepository.ModelRow;
import com.platform.relation.RelationGovernanceRepository.VersionRow;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 隔离 H2 与服务替身验证等价语义，不代表真实 MySQL 方言或现场性能验收。 */
class RelationMetadataCountTest {
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final String CURRENT = "current-version";
    private static final String HISTORY = "historical-version";
    private static final LocalDateTime EFFECTIVE_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
    private JdbcTemplate jdbc;
    private RelationGovernanceRepository repository;
    private BuildingScopeService scope;
    private RelationGovernanceService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:relation-metadata-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        // 故意使用忽略大小写的列，暴露直接 SQL 等值比较与 Java String.equals 的差异。
        jdbc.execute("""
                CREATE TABLE biz_metering_assignment_version_item (
                  assignment_item_id VARCHAR(64) PRIMARY KEY, version_id VARCHAR(64),
                  metering_boundary_id VARCHAR(64), meter_point_node_id VARCHAR(64),
                  target_node_id VARCHAR(64), allocation_status VARCHAR_IGNORECASE(24),
                  reason_code VARCHAR(64), reason_text VARCHAR(500),
                  evidence_reference VARCHAR(500), building_id VARCHAR(64))
                """);
        jdbc.execute("""
                CREATE TABLE biz_relation_validation_issue (
                  issue_id VARCHAR(64) PRIMARY KEY, version_id VARCHAR(64), issue_level VARCHAR(24),
                  issue_code VARCHAR(64), object_type VARCHAR(32), object_id VARCHAR(64),
                  message VARCHAR(500), detected_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE biz_data_point (point_id VARCHAR(64), point_type VARCHAR(24), unit VARCHAR(16))");
        jdbc.execute("CREATE TABLE biz_relation_node (node_id VARCHAR(64), business_object_id VARCHAR(64))");
        var staging = mock(RelationStagingRepository.class);
        repository = spy(new RelationGovernanceRepository(jdbc, new AuditGovernanceProperties(), staging));
        scope = mock(BuildingScopeService.class);
        when(scope.canAccess(101L, ENERGY, "BLD001")).thenReturn(true);
        service = new RelationGovernanceService(repository, scope, new RelationGovernanceProperties(),
                mock(MeteringDomainValidator.class), staging, mock(MySqlDataPointConfigProvider.class));
        var model = new ModelRow("model", "BUILDING", "BLD001", "BLD001", "GOVERNED", CURRENT, null, 73);
        doReturn(Optional.of(model)).when(repository).findModelByBuilding("BLD001", false);
        doReturn(Optional.of(version(CURRENT, "BLD001", 2, "EFFECTIVE")))
                .when(repository).findVersion(CURRENT, false);
        doReturn(Optional.of(version(HISTORY, "BLD001", 1, "SUPERSEDED")))
                .when(repository).findVersion(HISTORY, false);
        doReturn(List.of()).when(repository).listEffectiveMeteringAssignments(
                anyString(), eq("BLD001"), anyInt(), anyInt());
        doReturn(0L).when(repository).countMeterStructures(anyString());
        doReturn(List.of()).when(repository).listMeterStructures(anyString(), anyInt(), anyInt());
    }

    @AfterEach
    void closeDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void countMatchesOriginalListForTwentyNonMeterPointsDuplicatesAndAbnormalRelations() {
        seedAssignments();
        long original = repository.listMeteringAssignments(CURRENT).stream()
                .filter(row -> "UNASSIGNED".equals(row.allocationStatus())).count();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point WHERE point_type='TEMPERATURE'",
                Long.class)).isEqualTo(20);
        assertThat(original).isEqualTo(24);
        assertThat(repository.countUnassignedMeteringAssignments(CURRENT)).isEqualTo(original);
        assertThat(repository.countUnassignedMeteringAssignments(HISTORY)).isEqualTo(2);
        assertThat(repository.countUnassignedMeteringAssignments("missing")).isZero();
        assertThat(repository.countUnassignedMeteringAssignments("' OR 1=1 --")).isZero();
    }

    @Test
    void countRetainsExactCaseLengthNullAndUnicodeSemanticsUnderCaseInsensitiveStorage() {
        List<String> statuses = List.of("UNASSIGNED", "unassigned", "Unassigned", "UNASSIGNED ",
                " UNASSIGNED", "UNASSIGNED\u0000", "UNASSIGNEDX", "UNASSIGNE", "UNASSÍGNED",
                "ＵＮＡＳＳＩＧＮＥＤ", "ASSIGNED", "PENDING_EXPERT", "INVALID", "");
        for (int i = 0; i < statuses.size(); i++) {
            assignment("status-" + i, CURRENT, "BLD001", "point", statuses.get(i));
        }
        assignment("null-status", CURRENT, "BLD001", "point", null);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_metering_assignment_version_item
                WHERE version_id=? AND allocation_status='UNASSIGNED'
                """, Long.class, CURRENT)).isGreaterThan(1);
        assertThat(repository.countUnassignedMeteringAssignments(CURRENT)).isEqualTo(1);
        assertThat(repository.listMeteringAssignments(CURRENT).stream()
                .filter(row -> "UNASSIGNED".equals(row.allocationStatus())).count()).isEqualTo(1);
    }

    @Test
    void countUsesRequestedVersionAndObservesLaterChangesWithoutCachingOrDeduplication() {
        seedAssignments();
        assertThat(repository.countUnassignedMeteringAssignments(CURRENT)).isEqualTo(24);
        assertThat(repository.countUnassignedMeteringAssignments(HISTORY)).isEqualTo(2);
        jdbc.update("""
                UPDATE biz_metering_assignment_version_item SET allocation_status='ASSIGNED'
                WHERE assignment_item_id='duplicate-a'
                """);
        assertThat(repository.countUnassignedMeteringAssignments(CURRENT)).isEqualTo(23);
        assertThat(repository.countUnassignedMeteringAssignments(HISTORY)).isEqualTo(2);
    }

    @Test
    void metadataUsesScalarCountAndKeepsAllIssueLevelsVersionAndPaginationFields() {
        seedAssignments();
        issue("current-error-a", CURRENT, "ERROR");
        issue("current-error-b", CURRENT, "ERROR");
        issue("current-pending", CURRENT, "PENDING_EXPERT");
        issue("current-warning", CURRENT, "WARNING");
        issue("current-lowercase", CURRENT, "warning");
        issue("historical-warning", HISTORY, "WARNING");
        var originalIssues = repository.listIssues(CURRENT);

        var current = service.historicalMeteringAssignments(101L, ENERGY, "BLD001", CURRENT, 1, 10);
        assertThat(current.metadata()).isEqualTo(new QueryMetadata(
                "BLD001", CURRENT, 2, EFFECTIVE_AT, 73, 0, true, 24, 1, 2, 1, 100));
        var historical = service.historicalMeteringAssignments(101L, ENERGY, "BLD001", HISTORY, 1, 10);
        assertThat(historical.metadata()).isEqualTo(new QueryMetadata(
                "BLD001", HISTORY, 1, EFFECTIVE_AT, 73, 0, false, 2, 0, 0, 1, 100));
        assertThat(service.effectiveMeterStructures(101L, ENERGY, "BLD001", 1, 10)
                .metadata().unassignedCount()).isEqualTo(24);
        assertThat(service.historicalMeterStructures(101L, ENERGY, "BLD001", HISTORY, 1, 10)
                .metadata().unassignedCount()).isEqualTo(2);
        assertThat(repository.listIssues(CURRENT)).isEqualTo(originalIssues);
        verify(repository, never()).listMeteringAssignments(anyString());
    }

    @Test
    void deniedRoleCannotReadMetadata() {
        assertThatThrownBy(() -> service.historicalMeteringAssignments(
                101L, Set.of("UNAUTHORIZED"), "BLD001", CURRENT, 1, 10))
                .isInstanceOf(BusinessException.class);
        assertNoMetadataRead();
    }

    @Test
    void deniedBuildingCannotReadMetadata() {
        when(scope.canAccess(101L, ENERGY, "BLD001")).thenReturn(false);
        assertThatThrownBy(() -> service.historicalMeteringAssignments(
                101L, ENERGY, "BLD001", CURRENT, 1, 10)).isInstanceOf(BusinessException.class);
        assertNoMetadataRead();
    }

    @Test
    void crossBuildingHistoricalVersionCannotReadMetadata() {
        doReturn(Optional.of(version(HISTORY, "BLD002", 1, "SUPERSEDED")))
                .when(repository).findVersion(HISTORY, false);
        assertThatThrownBy(() -> service.historicalMeteringAssignments(
                101L, ENERGY, "BLD001", HISTORY, 1, 10)).isInstanceOf(BusinessException.class);
        assertNoMetadataRead();
    }

    private void assertNoMetadataRead() {
        verify(repository, never()).countUnassignedMeteringAssignments(anyString());
        verify(repository, never()).listIssues(anyString());
        verify(repository, never()).listMeteringAssignments(anyString());
    }

    private void seedAssignments() {
        for (int i = 0; i < 20; i++) {
            String point = "temperature-" + i;
            jdbc.update("INSERT INTO biz_data_point VALUES (?,'TEMPERATURE','°C')", point);
            jdbc.update("INSERT INTO biz_relation_node VALUES (?,?)", point + "-node", point);
            assignment(point, CURRENT, "BLD001", point + "-node", "UNASSIGNED");
        }
        assignment("duplicate-a", CURRENT, "BLD001", "same-meter-node", "UNASSIGNED");
        assignment("duplicate-b", CURRENT, "BLD001", "same-meter-node", "UNASSIGNED");
        assignment("orphan", CURRENT, "BLD001", null, "UNASSIGNED");
        assignment("abnormal-building", CURRENT, "BLD002", "unknown-node", "UNASSIGNED");
        assignment("assigned", CURRENT, "BLD001", "same-meter-node", "ASSIGNED");
        assignment("invalid", CURRENT, "BLD001", "unknown-node", "INVALID");
        assignment("pending", CURRENT, "BLD001", null, "PENDING_EXPERT");
        assignment("history-a", HISTORY, "BLD001", "same-meter-node", "UNASSIGNED");
        assignment("history-b", HISTORY, "BLD001", "same-meter-node", "UNASSIGNED");
    }

    private void assignment(String id, String version, String building, String point, String status) {
        jdbc.update("""
                INSERT INTO biz_metering_assignment_version_item
                  (assignment_item_id,version_id,building_id,meter_point_node_id,allocation_status)
                VALUES (?,?,?,?,?)
                """, id, version, building, point, status);
    }

    private void issue(String id, String version, String level) {
        jdbc.update("""
                INSERT INTO biz_relation_validation_issue
                  (issue_id,version_id,issue_level,issue_code,object_type,object_id,message,detected_at)
                VALUES (?,?,?,'SAME_CODE','POINT','same-node','synthetic issue',?)
                """, id, version, level, EFFECTIVE_AT);
    }

    private static VersionRow version(String id, String building, int number, String status) {
        return new VersionRow(id, "model", building, number, null, null, status,
                9L, null, "unchanged-snapshot", "synthetic fixture", 101L, null, null,
                null, EFFECTIVE_AT, EFFECTIVE_AT);
    }
}
