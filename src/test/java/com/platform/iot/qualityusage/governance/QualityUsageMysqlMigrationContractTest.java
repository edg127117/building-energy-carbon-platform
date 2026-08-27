package com.platform.iot.qualityusage.governance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL 增量脚本的静态契约，防止后续编辑放宽治理完整性或重跑边界。 */
class QualityUsageMysqlMigrationContractTest {
    private static final Path REVIEW_CLOSURE_MIGRATION = Path.of(
            "src/env/init/V28__close_quality_usage_direct_publication.sql");

    @Test
    void preservesPointerForeignKeysAndFormalVersionTimeConstraints() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("fk_quality_usage_policy_current_active_version")
                .contains("fk_quality_usage_policy_pending_review")
                .contains("fk_quality_usage_version_base_active")
                .contains("fk_quality_usage_version_copied_from")
                .contains("on delete restrict on update restrict")
                .contains("ensure_quality_usage_policy_governance_constraints")
                .contains("chk_quality_usage_formal_effective_from")
                .contains("chk_quality_usage_effective_to_alignment")
                .contains("chk_quality_usage_initial_baseline_shape")
                .contains("mod(`effective_from_ms`, 60000) = 0")
                .contains("mod(`effective_to_ms`, 60000) = 0")
                .contains("`effective_to_ms` >= `effective_from_ms`");
    }

    @Test
    void rerunVerifiesOnlyTheImmutableBaselineRatherThanLaterGovernanceState() throws IOException {
        String sql = migrationSql();
        int markerBranch = sql.indexOf("if v_marker_count = 1 then");
        int firstRunAliasValidation = sql.indexOf("select count(*) into v_alias_count");
        assertThat(markerBranch).isGreaterThanOrEqualTo(0);
        assertThat(firstRunAliasValidation).isGreaterThan(markerBranch);

        String markerSql = sql.substring(markerBranch, firstRunAliasValidation);
        assertThat(markerSql)
                .contains("v_policy_count <> 57")
                .contains("v_version_count <> 57")
                .contains("v_level_count <> 133")
                .contains("v_expected_level_count <> 133")
                .contains("v.`status` in ('active','retired')")
                .contains("v_revision < 1")
                .doesNotContain("s.`status` = 'enabled'");
    }

    @Test
    void reviewClosurePreservesHistoricalEvidenceButRejectsNewDirectPublicationByDefault() throws IOException {
        String sql = Files.readString(REVIEW_CLOSURE_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ADD COLUMN `legacy_direct_publish` TINYINT NOT NULL DEFAULT 0",
                "SET `legacy_direct_publish` = 1",
                "DROP CHECK `chk_quality_usage_review_mode`",
                "`review_mode` = 'NORMAL'",
                "`review_mode` = 'DIRECT_PUBLISH' AND `legacy_direct_publish` = 1");
        assertThat(sql).doesNotContain("sys_sensitive_change_request");
    }

    private static String migrationSql() throws IOException {
        return Files.readString(
                Path.of("src/env/init/V18__mysql_quality_usage_policy_governance.sql"),
                StandardCharsets.UTF_8).toLowerCase();
    }
}
