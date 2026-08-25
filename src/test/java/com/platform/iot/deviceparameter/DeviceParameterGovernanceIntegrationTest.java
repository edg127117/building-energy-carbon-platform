package com.platform.iot.deviceparameter;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateCommand;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ApplicabilityRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.CandidateCreateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ConflictResolutionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DefinitionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftCreateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftUpdateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DraftValueRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.MappingDraftRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.MappingRollbackRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ReviewDecisionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ReasonRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.SubmitRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.UnitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceParameterGovernanceIntegrationTest {
    private static final long AUTHOR = 1L;
    private static final long REVIEWER = 2L;
    private static final List<String> ADMIN = List.of("PLATFORM_ADMIN");
    private static final String EQUIPMENT_ID = "EQUIP_WCR_B1";

    @Autowired private DeviceParameterCatalogService catalogService;
    @Autowired private DeviceParameterCandidateService candidateService;
    @Autowired private DeviceParameterGovernanceService governanceService;
    @Autowired private DeviceParameterJdbcRepository repository;

    @Test
    void createsMappingRollbackAsNewDraftWithoutReactivatingHistory() {
        Definition definition = prepareCatalog();
        var first = catalogService.createMappingDraft(AUTHOR, ADMIN,
                new MappingDraftRequest("SYNTHETIC_PROFILE", "1", "ratedPower",
                        definition.definitionId(), "KW", BigDecimal.ONE, BigDecimal.ZERO,
                        true, "建立合成映射", "evidence://mapping", -1));
        var active = catalogService.publishMapping(AUTHOR, ADMIN,
                first.mappingVersionId(), 0);
        var rollback = catalogService.createMappingRollbackDraft(AUTHOR, ADMIN,
                new MappingRollbackRequest(active.mappingVersionId(), "验证映射回退",
                        "evidence://mapping-rollback", 1));

        assertThat(rollback.status()).isEqualTo("DRAFT");
        assertThat(rollback.versionNo()).isEqualTo(2);
        assertThat(rollback.copiedFromVersionId()).isEqualTo(active.mappingVersionId());
        assertThat(catalogService.listMappingVersions(
                AUTHOR, ADMIN, active.mappingVersionId())).hasSize(2);
        assertThat(repository.findMappingVersion(active.mappingVersionId()).orElseThrow().status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void governsFourSourcesConflictDutySeparationAndControlledRetroactiveTimeline() {
        Definition definition = prepareCatalog();
        var equipment = repository.findEquipment(EQUIPMENT_ID).orElseThrow();

        Candidate manual50 = candidateService.createManualCandidate(AUTHOR, ADMIN,
                manual("50", "manual-50"));
        Candidate template50 = candidateService.ingest(command(
                equipment, SourceType.TEMPLATE, "50", "template-50"));
        Candidate device55 = candidateService.ingest(command(
                equipment, SourceType.DEVICE, "55", "device-55"));
        Candidate excel50 = candidateService.ingest(command(
                equipment, SourceType.EXCEL, "50", "excel-50"));

        assertThat(List.of(manual50.sourceType(), template50.sourceType(),
                device55.sourceType(), excel50.sourceType()))
                .containsExactly(SourceType.MANUAL, SourceType.TEMPLATE,
                        SourceType.DEVICE, SourceType.EXCEL);
        var conflict = candidateService.listConflicts(AUTHOR, ADMIN, EQUIPMENT_ID)
                .getFirst();
        assertThat(conflict.members()).hasSize(4);
        candidateService.resolveConflict(AUTHOR, ADMIN, conflict.conflictId(),
                new ConflictResolutionRequest(manual50.candidateId(),
                        "以已核对的人工证据为首版", conflict.configRevision()));

        var firstDraft = governanceService.createDraft(AUTHOR, ADMIN, EQUIPMENT_ID,
                new DraftCreateRequest("INITIAL", "建立首版完整参数", "evidence://first"));
        firstDraft = governanceService.updateDraft(AUTHOR, ADMIN, firstDraft.versionId(),
                new DraftUpdateRequest(firstDraft.configRevision(), List.of(
                        new DraftValueRequest(definition.definitionId(), manual50.candidateId(),
                                "VALUE", null, null))));
        var firstReview = governanceService.submit(AUTHOR, ADMIN, firstDraft.versionId(),
                "submit-first", new SubmitRequest(firstDraft.configRevision(), "提交首版审核"));
        var withdrawn = governanceService.withdraw(AUTHOR, ADMIN, firstReview.requestId(),
                new ReasonRequest("补充审核说明").reason());
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        firstReview = governanceService.submit(AUTHOR, ADMIN, firstDraft.versionId(),
                "submit-first-again", new SubmitRequest(firstDraft.configRevision(), "重新提交首版审核"));
        var submittedFirstReview = firstReview;

        assertThatThrownBy(() -> governanceService.approve(AUTHOR, ADMIN,
                submittedFirstReview.requestId(), "approve-self",
                immediateDecision("拒绝自审")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(DeviceParameterErrors.REVIEW_CONFLICT));

        ReviewDecisionRequest firstDecision = immediateDecision("批准首版");
        governanceService.approve(REVIEWER, ADMIN, firstReview.requestId(),
                "approve-first", firstDecision);
        assertThat(governanceService.approve(REVIEWER, ADMIN, firstReview.requestId(),
                "approve-first", firstDecision).status()).isEqualTo("APPROVED");
        var firstEffective = governanceService.effectiveView(
                AUTHOR, ADMIN, EQUIPMENT_ID, null, null);
        assertThat(firstEffective.version().values().getFirst().value())
                .isEqualByComparingTo("50");
        assertThat(firstEffective.recalculationStatus()).isEqualTo("NOT_REQUIRED");

        Candidate manual55 = candidateService.createManualCandidate(AUTHOR, ADMIN,
                manual("55", "manual-55"));
        var changedConflict = candidateService.listConflicts(AUTHOR, ADMIN, EQUIPMENT_ID)
                .getFirst();
        candidateService.resolveConflict(AUTHOR, ADMIN, changedConflict.conflictId(),
                new ConflictResolutionRequest(manual55.candidateId(),
                        "追溯修正采用补充证明", changedConflict.configRevision()));

        var secondDraft = governanceService.createDraft(AUTHOR, ADMIN, EQUIPMENT_ID,
                new DraftCreateRequest("UPDATE", "追溯修正参数", "evidence://retro"));
        secondDraft = governanceService.updateDraft(AUTHOR, ADMIN, secondDraft.versionId(),
                new DraftUpdateRequest(secondDraft.configRevision(), List.of(
                        new DraftValueRequest(definition.definitionId(), manual55.candidateId(),
                                "VALUE", null, null))));
        var secondReview = governanceService.submit(AUTHOR, ADMIN, secondDraft.versionId(),
                "submit-retro", new SubmitRequest(secondDraft.configRevision(), "提交追溯审核"));
        LocalDateTime retroFrom = firstEffective.publishedAt();
        var impact = governanceService.previewRetroactiveImpact(REVIEWER, ADMIN,
                EQUIPMENT_ID, secondDraft.versionId(), retroFrom, null);
        governanceService.approve(REVIEWER, ADMIN, secondReview.requestId(),
                "approve-retro", new ReviewDecisionRequest("RETROACTIVE", retroFrom, null,
                        "厂家补充资料证明首版发布时即应采用修正值", "evidence://retro",
                        impact.fingerprint(), "批准受控追溯"));

        var currentKnowledge = governanceService.effectiveView(AUTHOR, ADMIN,
                EQUIPMENT_ID, retroFrom, null);
        var originalKnowledge = governanceService.effectiveView(AUTHOR, ADMIN,
                EQUIPMENT_ID, retroFrom, firstEffective.publishedAt());
        assertThat(currentKnowledge.version().values().getFirst().value())
                .isEqualByComparingTo("55");
        assertThat(originalKnowledge.version().values().getFirst().value())
                .isEqualByComparingTo("50");
        assertThat(currentKnowledge.timelineRevisionId())
                .isNotEqualTo(originalKnowledge.timelineRevisionId());
    }

    private Definition prepareCatalog() {
        catalogService.createUnit(AUTHOR, ADMIN,
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", -1));
        catalogService.updateUnit(AUTHOR, ADMIN, "KW",
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", 0), "ENABLED");
        Definition definition = catalogService.createDefinition(AUTHOR, ADMIN,
                new DefinitionRequest("RATED_POWER_TEST", "测试额定功率",
                        "仅用于自动化验证的合成标准参数", "POWER", "KW",
                        2, 1, "evidence://definition", -1));
        definition = catalogService.updateDefinition(AUTHOR, ADMIN, definition.definitionId(),
                new DefinitionRequest(definition.parameterCode(), definition.parameterName(),
                        definition.businessDefinition(), definition.quantityKind(),
                        definition.standardUnit(), definition.storageScale(), definition.displayScale(),
                        definition.evidenceReference(), 0), "ENABLED");
        catalogService.saveApplicability(AUTHOR, ADMIN,
                new ApplicabilityRequest("WCR", definition.definitionId(), true, false,
                        BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO,
                        new BigDecimal("1000"), BigDecimal.ZERO,
                        "evidence://applicability", -1), "ENABLED");
        return definition;
    }

    private static CandidateCreateRequest manual(String value, String key) {
        return new CandidateCreateRequest(EQUIPMENT_ID, "MANUAL", "evidence://" + key,
                "1", "ratedPower", value, "KW", null, "RATED_POWER_TEST",
                LocalDateTime.now(), key);
    }

    private static CandidateCommand command(
            DeviceParameterModels.EquipmentIdentity equipment,
            SourceType sourceType, String value, String key) {
        return new CandidateCommand(equipment, sourceType, "evidence://" + key, "1",
                "ratedPower", value, "KW", null, "RATED_POWER_TEST",
                LocalDateTime.now(), key, sourceType == SourceType.DEVICE ? null : AUTHOR);
    }

    private static ReviewDecisionRequest immediateDecision(String comment) {
        return new ReviewDecisionRequest("IMMEDIATE", null, null, null,
                "evidence://publish", null, comment);
    }
}
