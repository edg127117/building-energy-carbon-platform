package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定首版碳管理表、状态、职责和不预置业务因子的迁移边界。 */
class CarbonManagementMigrationContractTest {

    @Test
    void recoveryMigrationOnlyAddsLeasesMergeBoundaryAndRecoveryRelationship() throws IOException {
        String migration = Files.readString(Path.of("src", "env", "init",
                "V40__mysql_carbon_recalculation_recovery.sql"));
        assertThat(migration).contains("lease_token", "lease_until", "next_attempt_at",
                "attempt_count", "merge_key", "merge_ready_at", "parent_recalculation_batch_id");
        assertThat(migration).doesNotContain("INSERT INTO", "DELETE FROM", "DROP TABLE");
    }

    @Test
    void definesVersionedRulesResultsAndPersistentRecalculationWithoutBusinessSeeds()
            throws IOException {
        String migration = Files.readString(Path.of("src", "env", "init",
                "V39__mysql_carbon_management_foundation.sql"));

        assertThat(migration).contains(
                "CREATE TABLE `biz_carbon_factor_source_version`",
                "CREATE TABLE `biz_carbon_factor_version`",
                "CREATE TABLE `biz_carbon_denominator_version`",
                "CREATE TABLE `biz_carbon_calculation_batch`",
                "CREATE TABLE `biz_carbon_calculation_item`",
                "CREATE TABLE `biz_carbon_result_relation`",
                "CREATE TABLE `biz_carbon_dependency_change`",
                "CREATE TABLE `biz_carbon_recalculation_batch`",
                "CREATE TABLE `biz_carbon_recalculation_item`",
                "'DEVELOPMENT_SIMULATION','FORMAL'",
                "'PENDING_REVIEW','APPROVED','ACTIVE','DISABLED','REJECTED'",
                "'DIRECT','CANDIDATE','PUBLISHED','SUPERSEDED'",
                "'PENDING_CALCULATION','CALCULATING','FAILED_RETRYABLE','PENDING_APPROVAL'",
                "'PENDING','CALCULATING','SUCCEEDED','FAILED_RETRYABLE','DEAD','PUBLISHED','REJECTED'",
                "`denominator_value` > 0",
                "`effective_to` > `effective_from`",
                "'CARBON_RECALCULATION_APPROVE'");
        assertThat(migration).contains(
                "INSERT INTO `biz_carbon_formula_version`",
                "INSERT INTO `biz_carbon_gwp_version`",
                "INSERT INTO `biz_carbon_rounding_policy_version`");
        assertThat(migration).doesNotContain(
                "INSERT INTO `biz_carbon_factor`",
                "INSERT INTO `biz_carbon_factor_version`",
                "INSERT INTO `biz_carbon_denominator`",
                "INSERT INTO `biz_carbon_denominator_version`");
    }
}
