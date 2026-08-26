package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定关系治理的结构边界，禁止迁移预置业务关系或分摊规则。 */
class RelationGovernanceMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src", "env", "init", "V24__mysql_relation_governance.sql");
    private static final Path TEST_SCHEMA = Path.of(
            "src", "test", "resources", "schema-test.sql");
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

    @Test
    void migrationDefinesExactlyTheRelationGovernanceTablesAndStates() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(RELATION_TABLES).allSatisfy(
                table -> assertThat(sql).contains("CREATE TABLE " + table));
        assertThat(Pattern.compile("(?m)^CREATE TABLE\\s+").matcher(sql)
                .results().count()).isEqualTo(RELATION_TABLES.size());
        assertThat(Pattern.compile("COLLATE=utf8mb4_0900_ai_ci", Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().count()).isEqualTo(RELATION_TABLES.size());
        assertThat(List.of(
                "DRAFT", "PENDING_REVIEW", "APPROVED", "EFFECTIVE", "SUPERSEDED", "REJECTED", "WITHDRAWN",
                "BUILDING", "CAMPUS", "LEGACY", "GOVERNED",
                "SERVES", "MEASURES", "CONNECTED_TO", "SUPPLIES", "RETURNS",
                "ASSIGNED", "UNASSIGNED", "PENDING_EXPERT", "INVALID"))
                .allSatisfy(state -> assertThat(sql).contains("'" + state + "'"));
    }

    @Test
    void migrationContainsNoAllocationRuleOrBusinessFactSeed() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        assertThat(Pattern.compile(
                "\\b(?:ratio|weight|formula|rule_expression)\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find()).isFalse();
        assertThat(sql).doesNotContain("insert into", "update ", "delete from");
    }

    @Test
    void h2TestSchemaMirrorsEveryRelationGovernanceTable() throws IOException {
        String schema = Files.readString(TEST_SCHEMA, StandardCharsets.UTF_8);

        assertThat(RELATION_TABLES).allSatisfy(
                table -> assertThat(schema).contains("CREATE TABLE " + table));
    }
}
