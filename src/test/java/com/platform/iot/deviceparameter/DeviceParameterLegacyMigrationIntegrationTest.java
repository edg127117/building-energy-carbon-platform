package com.platform.iot.deviceparameter;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.deviceparameter.DeviceParameterModels.ChangeType;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ApplicabilityRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DefinitionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.LegacyMappingRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.UnitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceParameterLegacyMigrationIntegrationTest {
    private static final long ADMIN_USER = 1L;
    private static final List<String> ADMIN = List.of("PLATFORM_ADMIN");
    private static final String EQUIPMENT_ID = "EQUIP_WCR_B1";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceParameterCatalogService catalogService;
    @Autowired private DeviceParameterLegacyMigrationService migrationService;
    @Autowired private DeviceParameterGovernanceService governanceService;
    @Autowired private DeviceParameterJdbcRepository repository;

    @Test
    void confirmedMappingCreatesIdempotentMigrationDraftButNeverPublishesIt() {
        Definition definition = prepareCatalog();
        catalogService.saveLegacyMapping(ADMIN_USER, ADMIN,
                new LegacyMappingRequest("WCR", "rated_power", "MAPPED",
                        definition.definitionId(), "KW", BigDecimal.ONE, BigDecimal.ZERO,
                        "evidence://legacy-field-unit", -1), "ENABLED");
        jdbc.update("""
                UPDATE biz_equipment SET rated_capacity=NULL,rated_power=60,design_cop=NULL
                WHERE equip_id=?
                """, EQUIPMENT_ID);

        var first = migrationService.execute(ADMIN_USER, ADMIN, "legacy-test-1");
        var second = migrationService.execute(ADMIN_USER, ADMIN, "legacy-test-1");
        var versions = governanceService.listVersions(ADMIN_USER, ADMIN, EQUIPMENT_ID);

        assertThat(first.stagedFactCount()).isEqualTo(1);
        assertThat(first.readyFactCount()).isEqualTo(1);
        assertThat(first.draftCount()).isEqualTo(1);
        assertThat(first.automaticallyPublished()).isFalse();
        assertThat(second.stagedFactCount()).isEqualTo(1);
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().changeType()).isEqualTo(ChangeType.MIGRATION);
        assertThat(versions.getFirst().status()).isEqualTo(DeviceParameterModels.VersionStatus.DRAFT);
        assertThat(versions.getFirst().values().getFirst().sourceType())
                .isEqualTo(SourceType.LEGACY_MIGRATION);
        assertThatThrownBy(() -> governanceService.effective(
                ADMIN_USER, ADMIN, EQUIPMENT_ID, null, null))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.countLegacyStaging("legacy-test-1")).isEqualTo(1);
    }

    private Definition prepareCatalog() {
        catalogService.createUnit(ADMIN_USER, ADMIN,
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", -1));
        catalogService.updateUnit(ADMIN_USER, ADMIN, "KW",
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", 0), "ENABLED");
        Definition definition = catalogService.createDefinition(ADMIN_USER, ADMIN,
                new DefinitionRequest("LEGACY_RATED_POWER_TEST", "迁移额定功率",
                        "仅用于验证旧字段迁移边界", "POWER", "KW",
                        2, 1, "evidence://definition", -1));
        definition = catalogService.updateDefinition(ADMIN_USER, ADMIN,
                definition.definitionId(), new DefinitionRequest(
                        definition.parameterCode(), definition.parameterName(),
                        definition.businessDefinition(), definition.quantityKind(),
                        definition.standardUnit(), definition.storageScale(),
                        definition.displayScale(), definition.evidenceReference(), 0), "ENABLED");
        catalogService.saveApplicability(ADMIN_USER, ADMIN,
                new ApplicabilityRequest("WCR", definition.definitionId(), true, false,
                        BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO,
                        new BigDecimal("1000"), BigDecimal.ZERO,
                        "evidence://applicability", -1), "ENABLED");
        return definition;
    }
}
