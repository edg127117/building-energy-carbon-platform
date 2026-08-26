package com.platform.relation;

import com.platform.relation.api.RelationContracts.ActivationRequest;
import com.platform.relation.api.RelationContracts.AssetAssignmentRequest;
import com.platform.relation.api.RelationContracts.ReviewDecisionRequest;
import com.platform.relation.api.RelationContracts.RevisionReasonRequest;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** 专用隔离 MySQL 验证 V24 真实方言、约束和关系版本生效事务。 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql",
        "spring.sql.init.mode=never",
        "database.charset-fix.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RELATION_MYSQL_IT_URL", matches = ".+")
class RelationGovernanceMysqlIntegrationTest {
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final List<String> RELATION_TABLES = List.of(
            "biz_relation_model",
            "biz_relation_version",
            "biz_metering_boundary",
            "biz_relation_node",
            "biz_space_parent_version_item",
            "biz_asset_assignment_version_item",
            "biz_semantic_relation_version_item",
            "biz_metering_assignment_version_item",
            "biz_relation_review_request",
            "biz_relation_validation_issue",
            "biz_relation_audit_log");

    @Autowired private RelationGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private BuildingScopeService buildingScopeService;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("RELATION_MYSQL_IT_URL"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("RELATION_MYSQL_IT_USER", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("RELATION_MYSQL_IT_PASSWORD", "test-root"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void setUp() {
        assertThat(System.getenv("RELATION_MYSQL_IT_ISOLATED"))
                .as("集成测试必须由调用方显式确认使用一次性隔离 MySQL")
                .isEqualTo("true");
        assertThat(System.getenv("RELATION_MYSQL_IT_URL"))
                .as("隔离集成测试固定使用项目数据库名")
                .contains("/iot_platform");
        when(buildingScopeService.canAccess(anyLong(), any(Collection.class), any()))
                .thenReturn(true);
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_relation_projection");
    }

    @Test
    void migratesV24AndKeepsActivationAtomicOnMysql() {
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("24");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class)).containsAll(RELATION_TABLES);
        assertThat(RELATION_TABLES).allSatisfy(table -> assertThat(jdbc.queryForObject("""
                SELECT table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table)).isEqualTo("utf8mb4_0900_ai_ci"));
        assertThat(jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE()
                """, String.class)).contains(
                        "fk_relation_model_building",
                        "fk_relation_assignment_equipment",
                        "fk_semantic_relation_source_node",
                        "fk_metering_assignment_boundary_building");

        jdbc.update("""
                INSERT INTO biz_space
                  (space_id,building_id,parent_space_id,space_name,space_code,
                   space_type,floor_level,del_flag)
                VALUES ('SPACE_MYSQL_IT','BLD001',NULL,'MySQL事务测试空间',
                        'MYSQL_IT','ROOM',1,0)
                """);
        var draft = service.initialize(101L, ENERGY, "BLD001", "mysql-init", "MySQL初始化");
        var updated = service.updateAssignment(101L, ENERGY, draft.versionId(),
                new AssetAssignmentRequest("EQUIPMENT", "EQUIP_WCR_B1", "SPACE_MYSQL_IT",
                        "GROUP001", null, draft.revision()));
        var review = service.submit(101L, ENERGY, draft.versionId(), "mysql-submit",
                new RevisionReasonRequest(updated.revision(), "MySQL提交"));
        service.approve(202L, ADMIN, review.requestId(), "mysql-approve",
                new ReviewDecisionRequest("MySQL结构与关系校验通过"));

        long modelRevision = service.model(202L, ADMIN, "BLD001").modelRevision();
        jdbc.execute("""
                CREATE TRIGGER fail_relation_projection BEFORE UPDATE ON biz_equipment
                FOR EACH ROW SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT='forced relation projection failure'
                """);
        assertThatThrownBy(() -> service.activate(202L, ADMIN, draft.versionId(), "mysql-activate-fail",
                new ActivationRequest(modelRevision, "验证生效回滚")))
                .isInstanceOf(DataAccessException.class);

        assertThat(service.model(202L, ADMIN, "BLD001").governanceMode()).isEqualTo("LEGACY");
        assertThat(service.versions(202L, ADMIN, "BLD001").getFirst().status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT space_id FROM biz_equipment WHERE equip_id='EQUIP_WCR_B1'",
                String.class)).isEqualTo("SPACE001");

        jdbc.execute("DROP TRIGGER fail_relation_projection");
        var effective = service.activate(202L, ADMIN, draft.versionId(), "mysql-activate-ok",
                new ActivationRequest(modelRevision, "验证原子生效"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT space_id FROM biz_equipment WHERE equip_id='EQUIP_WCR_B1'",
                String.class)).isEqualTo("SPACE_MYSQL_IT");
    }
}
