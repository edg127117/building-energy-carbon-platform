package com.platform.energy.period;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.classification.ClassificationContracts.Reading;
import com.platform.energy.classification.ClassificationRepository;
import com.platform.energy.period.EnergyPeriodModels.CurrentProjection;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import com.platform.energy.period.EnergyPeriodModels.ProjectionCalculation;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.ModelView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import com.platform.relation.api.RelationContracts.SnapshotCounts;
import com.platform.relation.api.RelationContracts.VersionDetailView;
import com.platform.relation.api.RelationContracts.VersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualPeriodInputServiceTest {
    private static final long USER = 101L;
    private static final String BUILDING = "BLD001";
    private static final String POINT = "POINT001";
    private static final Collection<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final LocalDate MONTH = LocalDate.of(2020, 1, 1);
    private static final Instant SHANGHAI_START = Instant.parse("2019-12-31T16:00:00Z");
    private static final Instant SHANGHAI_END = Instant.parse("2020-01-31T16:00:00Z");
    private static final Instant SHANGHAI_MIDDLE = Instant.parse("2020-01-15T16:00:00Z");

    @Mock private EnergyPeriodAuthorization authorization;
    @Mock private ClassificationRepository manualRepository;
    @Mock private EnergyCatalogService catalog;
    @Mock private EnergyPeriodGovernanceService policies;
    @Mock private RelationGovernanceService relations;

    private ObjectMapper mapper;
    private ManualPeriodInputService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new ManualPeriodInputService(authorization, manualRepository, catalog, policies,
                relations, mapper);
    }

    @Test
    void preparesStableFormalNativeMonthFromContinuousReviewedSources() throws Exception {
        List<ClassificationRepository.StoredReading> readings = validShanghaiReadings();
        arrangeFormalShanghai(readings.reversed(), readings);
        arrangeConfirmedShanghaiBinding();

        ProjectionCalculation first = service.prepare(USER, ROLES, BUILDING, request());
        ProjectionCalculation second = service.prepare(USER, ROLES, BUILDING, request());

        assertThat(first.resultNature()).isEqualTo("FORMAL");
        assertThat(first.window().startInclusive()).isEqualTo(SHANGHAI_START);
        assertThat(first.window().endExclusive()).isEqualTo(SHANGHAI_END);
        assertThat(first.nativeQuantity()).isEqualByComparingTo("300");
        assertThat(first.nativeUnitCode()).isEqualTo("kWh");
        assertThat(first.tce()).isNull();
        assertThat(first.tceUnitCode()).isNull();
        assertThat(first.evidenceJson()).isEqualTo(second.evidenceJson());
        assertThat(first.evidenceHash()).isEqualTo(second.evidenceHash());
        var evidence = mapper.readTree(first.evidenceJson());
        assertThat(evidence.path("inputKind").asText()).isEqualTo("REVIEWED_MANUAL_MONTH");
        assertThat(evidence.path("batchIds").get(0).asText()).isEqualTo("IMPORT-A");
        assertThat(evidence.path("batchIds").get(1).asText()).isEqualTo("IMPORT-B");

        verify(authorization, times(2)).requireReader(ROLES);
        verify(authorization, times(2)).checkBuilding(USER, ROLES, BUILDING);
        verify(manualRepository, times(2)).lock(BUILDING);
        verify(manualRepository, times(2)).readings(BUILDING, SHANGHAI_START, SHANGHAI_END, true);
        verify(relations, never()).versions(anyLong(), any(), anyString());
    }

    @Test
    void rejectsApprovedSimulationPolicyInsteadOfUsingItAsFormalInput() {
        when(policies.periodPolicyVersion("POLICY-1"))
                .thenReturn(policy("Asia/Shanghai", "APPROVED", "SIMULATION", null));

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verify(authorization).requireReader(ROLES);
        verify(authorization).checkBuilding(USER, ROLES, BUILDING);
        verify(manualRepository).lock(BUILDING);
        verify(manualRepository, never()).readings(anyString(), any(), any(), eq(true));
        verifyNoInteractions(relations, catalog);
    }

    @Test
    void rejectsUnapprovedFormalPolicyBeforeReadingManualSources() {
        when(policies.periodPolicyVersion("POLICY-1"))
                .thenReturn(policy("Asia/Shanghai", "PENDING_REVIEW", "FORMAL", null));

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verify(manualRepository, never()).readings(anyString(), any(), any(), eq(true));
        verifyNoInteractions(relations, catalog);
    }

    @Test
    void rejectsGapBetweenOtherwiseExactReviewedReadings() {
        Instant gapStart = SHANGHAI_MIDDLE.plusSeconds(1);
        List<ClassificationRepository.StoredReading> readings = List.of(
                stored("F1", "IMPORT-A", reading(SHANGHAI_START, SHANGHAI_MIDDLE,
                        "100", "EXACT")),
                stored("F2", "IMPORT-B", reading(gapStart, SHANGHAI_END, "200", "EXACT")));
        arrangeFormalShanghai(readings);

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsNonExactReviewedReading() {
        List<ClassificationRepository.StoredReading> readings = List.of(stored("F1", "IMPORT-A",
                reading(SHANGHAI_START, SHANGHAI_END, "300", "DAY")));
        arrangeFormalShanghai(readings);
        ManualPeriodInputService.Request oneFact = request(List.of("F1"));

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, oneFact))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsZeroLengthReadingThatWouldOtherwiseInjectExtraNativeQuantity() {
        List<ClassificationRepository.StoredReading> readings = List.of(
                stored("F1", "IMPORT-A", reading(SHANGHAI_START, SHANGHAI_START,
                        "10", "EXACT")),
                stored("F2", "IMPORT-B", reading(SHANGHAI_START, SHANGHAI_END,
                        "300", "EXACT")));
        arrangeFormalShanghai(readings);

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsWhenConfirmedCatalogBindingChangesWithinTheMonth() {
        List<ClassificationRepository.StoredReading> readings = validShanghaiReadings();
        arrangeFormalShanghai(readings);
        when(catalog.effectiveBinding(USER, ROLES, BUILDING, POINT, LocalDateTime.of(2020, 1, 1, 0, 0)))
                .thenReturn(binding("BINDING-1"));
        when(catalog.effectiveBinding(USER, ROLES, BUILDING, POINT,
                LocalDateTime.of(2020, 1, 31, 23, 59, 59, 999_999_999)))
                .thenReturn(binding("BINDING-2"));

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);
    }

    @Test
    void rejectsRelationThatStartsAfterUtcMonthBoundaryUsingPlatformTimeConvention() {
        when(policies.periodPolicyVersion("POLICY-1"))
                .thenReturn(policy("UTC", "APPROVED", "FORMAL", null));
        LocalDateTime relationStartsAtPlatformTime = LocalDateTime.of(2020, 1, 1, 9, 0);
        arrangeRelation(relationStartsAtPlatformTime, "REL-1");

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verify(manualRepository, never()).readings(anyString(), any(), any(), eq(true));
        verifyNoInteractions(catalog);
    }

    @Test
    void verifyStoredBlocksWhenApprovedFactIsNoLongerAvailableAfterSupersession() {
        List<ClassificationRepository.StoredReading> readings = validShanghaiReadings();
        arrangeFormalShanghai(readings, List.of());
        arrangeConfirmedShanghaiBinding();
        ProjectionCalculation frozen = service.prepare(USER, ROLES, BUILDING, request());

        assertThatThrownBy(() -> service.verifyStored(USER, ROLES, current(frozen)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);

        verify(manualRepository, times(2)).readings(BUILDING, SHANGHAI_START, SHANGHAI_END, true);
    }

    @Test
    void sealedFactIdsDeduplicatesNestedManualEvidenceToPreventDoubleCounting() {
        String lockedEvidence = """
                {"calculationEvidence":{"calculationEvidence":{"inputKind":"REVIEWED_MANUAL_MONTH",
                "request":{"factIds":["F2","F1","F2"]}}}}""";

        assertThat(ManualPeriodInputService.sealedFactIds(mapper, lockedEvidence))
                .containsExactlyInAnyOrder("F1", "F2");
    }

    @Test
    void stopsBeforeLockingSourcesWhenReaderAuthorizationFails() {
        doThrow(EnergyPeriodErrors.error(403, EnergyPeriodErrors.FORBIDDEN, "forbidden"))
                .when(authorization).requireReader(ROLES);

        assertThatThrownBy(() -> service.prepare(USER, ROLES, BUILDING, request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EnergyPeriodErrors.FORBIDDEN);

        verify(authorization).requireReader(ROLES);
        verify(authorization, never()).checkBuilding(USER, ROLES, BUILDING);
        verifyNoInteractions(manualRepository, policies, relations, catalog);
    }

    private void arrangeFormalShanghai(List<ClassificationRepository.StoredReading> readings) {
        arrangeFormalShanghai(readings, readings);
    }

    private void arrangeFormalShanghai(List<ClassificationRepository.StoredReading> first,
                                        List<ClassificationRepository.StoredReading> second) {
        when(policies.periodPolicyVersion("POLICY-1"))
                .thenReturn(policy("Asia/Shanghai", "APPROVED", "FORMAL", null));
        arrangeRelation(LocalDateTime.of(2019, 12, 1, 0, 0), "REL-1");
        when(manualRepository.readings(BUILDING, SHANGHAI_START, SHANGHAI_END, true))
                .thenReturn(first, second);
    }

    private void arrangeConfirmedShanghaiBinding() {
        BindingVersionView binding = binding("BINDING-1");
        when(catalog.effectiveBinding(USER, ROLES, BUILDING, POINT,
                LocalDateTime.of(2020, 1, 1, 0, 0))).thenReturn(binding);
        when(catalog.effectiveBinding(USER, ROLES, BUILDING, POINT,
                LocalDateTime.of(2020, 1, 31, 23, 59, 59, 999_999_999))).thenReturn(binding);
    }

    private void arrangeRelation(LocalDateTime effectiveAt, String activeVersionId) {
        when(relations.versionDetail(USER, ROLES, BUILDING, "REL-1"))
                .thenReturn(relation(effectiveAt));
        when(relations.model(USER, ROLES, BUILDING)).thenReturn(new ModelView("MODEL-1", BUILDING,
                "BUILDING", "FORMAL", activeVersionId, null, 1L));
    }

    private List<ClassificationRepository.StoredReading> validShanghaiReadings() {
        return List.of(
                stored("F1", "IMPORT-A", reading(SHANGHAI_START, SHANGHAI_MIDDLE,
                        "100", "EXACT")),
                stored("F2", "IMPORT-B", reading(SHANGHAI_MIDDLE, SHANGHAI_END,
                        "200", "EXACT")));
    }

    private Reading reading(Instant start, Instant end, String quantity, String precision) {
        return new Reading(POINT, start, end, "GRID_ELECTRICITY", "kWh", "PERIOD_TOTAL",
                null, null, new BigDecimal(quantity), BigDecimal.ONE, false, "CONFIRMED",
                "manual-evidence", precision, null);
    }

    private ClassificationRepository.StoredReading stored(String factId, String batchId,
                                                           Reading reading) {
        return new ClassificationRepository.StoredReading(factId, batchId, json(reading),
                reading.quantity(), USER, "APPROVED");
    }

    private PeriodPolicyVersion policy(String timezone, String status, String sourceType,
                                       LocalDateTime effectiveTo) {
        return new PeriodPolicyVersion("POLICY", "POLICY-1", 1, BUILDING, timezone,
                0, "REVIEW_REQUIRED", status, sourceType, "approved-formal-evidence",
                LocalDateTime.of(2019, 1, 1, 0, 0), effectiveTo, 1, USER,
                LocalDateTime.of(2019, 1, 1, 0, 0), USER + 1,
                LocalDateTime.of(2019, 1, 2, 0, 0));
    }

    private VersionDetailView relation(LocalDateTime effectiveAt) {
        VersionView version = new VersionView("REL-1", "MODEL-1", BUILDING, 1, null, null,
                "EFFECTIVE", 1L, 1L, "RELATION-HASH", "approved", USER, USER, USER + 1,
                USER + 1, LocalDateTime.of(2019, 1, 1, 0, 0), effectiveAt);
        QueryMetadata metadata = new QueryMetadata(BUILDING, "REL-1", 1, effectiveAt,
                1L, 0, false, 0, 0, 0, 0);
        return new VersionDetailView(metadata, version, new SnapshotCounts(0, 0, 0, 0, 0, 0, 0),
                List.of());
    }

    private BindingVersionView binding(String versionId) {
        LocalDateTime now = LocalDateTime.of(2019, 1, 1, 0, 0);
        return new BindingVersionView("BINDING", versionId, 1, BUILDING, POINT, "P-001",
                "kWh", "GRID_ELECTRICITY", "ITEM-V1", now, null, "CONFIRMED",
                "binding-evidence", 1, USER, now, USER + 1, now);
    }

    private ManualPeriodInputService.Request request() {
        return request(List.of("F2", "F1"));
    }

    private ManualPeriodInputService.Request request(List<String> facts) {
        return new ManualPeriodInputService.Request(POINT, MONTH, "POLICY-1", "REL-1", facts,
                0L, "已审核人工来源形成正式候选月量");
    }

    private CurrentProjection current(ProjectionCalculation value) {
        return new CurrentProjection("CURRENT-1", "RESULT-1", BUILDING, POINT, "MONTH",
                value.window().startInclusive(), value.window().endExclusive(),
                value.window().timezoneId(), value.periodPolicyVersionId(), "PROVISIONAL", 1L,
                value.resultNature(), value.energyItemCode(), value.nativeQuantity(),
                value.nativeUnitCode(), value.tce(), value.tceUnitCode(), value.coverageRatio(),
                "", value.evidenceJson(), value.evidenceHash(), null, value.activityWatermark(),
                value.calculatedAt(), value.calculatedAt());
    }

    private String json(Reading value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
