package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.carbon.CarbonConfigurationGuideService.Guide;
import com.platform.carbon.CarbonConfigurationGuideService.Step;
import com.platform.carbon.CarbonConfigurationWorkOrderService.Command;
import com.platform.carbon.CarbonConfigurationWorkOrderService.Receipt;
import com.platform.carbon.CarbonProjectContracts.Configuration;
import com.platform.carbon.CarbonProjectContracts.Transition;
import com.platform.carbon.CarbonProjectContracts.Version;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodCalculationService;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.ReviewDecisionRequest;
import com.platform.relation.api.RelationContracts.ReviewView;
import com.platform.relation.api.RelationContracts.VersionDetailView;
import com.platform.relation.api.RelationContracts.VersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarbonConfigurationWorkOrderServiceTest {
    private static final long USER = 101L;
    private static final long OTHER_USER = 202L;
    private static final String BUILDING = "BLD001";
    private static final String PROJECT = "PROJECT-1";
    private static final String RELATION = "RELATION-1";
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER", "PLATFORM_ADMIN");

    @Mock private CarbonConfigurationGuideService guides;
    @Mock private CarbonProjectService projects;
    @Mock private ClassificationService classifications;
    @Mock private RelationGovernanceService relations;
    @Mock private CarbonProjectRepository projectRepository;
    @Mock private CarbonWorkOrderRepository commands;
    @Mock private AuditEvidenceWriter audit;
    @Mock private AuditGovernanceProperties auditProperties;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CarbonConfigurationWorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new CarbonConfigurationWorkOrderService(guides, projects, classifications, relations,
                mapper, projectRepository, commands, audit, auditProperties);
    }

    @Test
    void replaysSameAuthorAndPayloadWithoutSecondDomainOperation() throws Exception {
        Guide guide = guide(projectStep("REVIEW_REQUIRED", "SUBMIT"));
        Command command = command(guide, "PROJECT", "SUBMIT", "提交审核", "command-1", null);
        Receipt receipt = new Receipt(BUILDING, PROJECT, "PROJECT", "SUBMIT", PROJECT,
                "PENDING_REVIEW", 8L);
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);
        when(commands.find(BUILDING, PROJECT, command.idempotencyKey())).thenReturn(null,
                new CarbonWorkOrderRepository.Stored(hash(command), USER, mapper.writeValueAsString(receipt)));
        when(projects.transition(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), eq("submit"),
                any(Transition.class))).thenReturn(project("PENDING_REVIEW", 8L));

        Receipt first = service.execute(USER, ROLES, BUILDING, PROJECT, command);
        Receipt replayed = service.execute(USER, ROLES, BUILDING, PROJECT, command);

        assertThat(first).isEqualTo(receipt);
        assertThat(replayed).isEqualTo(receipt);
        verify(projects, times(1)).transition(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT),
                eq("submit"), any(Transition.class));
        verify(commands, times(1)).insert(anyString(), eq(BUILDING), eq(PROJECT),
                eq(command.idempotencyKey()), anyString(), anyString(), anyString(), eq(USER));
        verify(audit, times(1)).append(any());
        verify(guides, times(1)).guide(USER, ROLES, BUILDING, PROJECT);
    }

    @Test
    void rejectsSameIdempotencyKeyForDifferentPayloadOrAuthor() throws Exception {
        Guide guide = guide(projectStep("REVIEW_REQUIRED", "SUBMIT"));
        Command original = command(guide, "PROJECT", "SUBMIT", "提交审核", "command-1", null);
        Command changed = command(guide, "PROJECT", "SUBMIT", "改了原因", "command-1", null);
        CarbonWorkOrderRepository.Stored stored = new CarbonWorkOrderRepository.Stored(
                hash(original), USER, mapper.writeValueAsString(new Receipt(BUILDING, PROJECT,
                "PROJECT", "SUBMIT", PROJECT, "PENDING_REVIEW", 8L)));
        arrangeProject(USER);
        arrangeProject(OTHER_USER);
        when(commands.find(BUILDING, PROJECT, "command-1")).thenReturn(stored);

        assertThatThrownBy(() -> service.execute(USER, ROLES, BUILDING, PROJECT, changed))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> service.execute(OTHER_USER, ROLES, BUILDING, PROJECT, original))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.IDEMPOTENCY_CONFLICT);

        verify(projects, never()).transition(anyLong(), any(), anyString(), anyString(),
                anyString(), any(Transition.class));
        verify(commands, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(guides, classifications, relations, audit);
    }

    @Test
    void staleFingerprintStopsBeforeDomainMutationReceiptAndAudit() throws Exception {
        Guide guide = guide(projectStep("REVIEW_REQUIRED", "SUBMIT"));
        Command stale = new Command("stale-fingerprint", "PROJECT", "SUBMIT", "提交审核", null,
                "command-1");
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);

        assertThatThrownBy(() -> service.execute(USER, ROLES, BUILDING, PROJECT, stale))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.VERSION_CONFLICT);

        verify(projects, never()).transition(anyLong(), any(), anyString(), anyString(),
                anyString(), any(Transition.class));
        verify(commands, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(classifications, relations, audit);
    }

    @Test
    void propagatesOriginalDomain403WithoutWritingReceiptOrAudit() throws Exception {
        Guide guide = guide(projectStep("REVIEW_REQUIRED", "SUBMIT"));
        Command command = command(guide, "PROJECT", "SUBMIT", "提交审核", "command-1", null);
        BusinessException original = new BusinessException(403, "DOMAIN_FORBIDDEN", "原领域拒绝操作");
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);
        when(projects.transition(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), eq("submit"),
                any(Transition.class))).thenThrow(original);

        assertThatThrownBy(() -> service.execute(USER, ROLES, BUILDING, PROJECT, command))
                .isSameAs(original);

        verify(commands, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void mapsProjectActionToLowerCaseAndForwardsTransitionParameters() throws Exception {
        Guide guide = guide(projectStep("REVIEW_REQUIRED", "REVIEW"));
        Instant effectiveFrom = Instant.parse("2026-10-20T01:02:03Z");
        Command command = command(guide, "PROJECT", "APPROVE", "独立审核通过", "command-1", effectiveFrom);
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);
        when(projects.transition(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), anyString(),
                any(Transition.class))).thenReturn(project("APPROVED", 8L));

        service.execute(USER, ROLES, BUILDING, PROJECT, command);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Transition> transition = ArgumentCaptor.forClass(Transition.class);
        verify(projects).transition(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), action.capture(),
                transition.capture());
        assertThat(action.getValue()).isEqualTo("approve");
        assertThat(transition.getValue().expectedRevision()).isEqualTo(7L);
        assertThat(transition.getValue().reason()).isEqualTo("独立审核通过");
        assertThat(transition.getValue().effectiveFrom()).isEqualTo(effectiveFrom);
    }

    @Test
    void blocksUnknownAndBlockedGuideStepsWithoutDomainOperation() throws Exception {
        Guide unknown = guide(projectStep("UNKNOWN", "VIEW"));
        Guide blocked = guide(projectStep("BLOCKED", "RESOLVE_ISSUES"));
        Command unknownCommand = command(unknown, "PROJECT", "SUBMIT", "提交审核", "unknown-key", null);
        Command blockedCommand = command(blocked, "PROJECT", "SUBMIT", "提交审核", "blocked-key", null);
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(unknown, blocked);

        assertThatThrownBy(() -> service.execute(USER, ROLES, BUILDING, PROJECT, unknownCommand))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.STATUS_CONFLICT);
        assertThatThrownBy(() -> service.execute(USER, ROLES, BUILDING, PROJECT, blockedCommand))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.STATUS_CONFLICT);

        verify(projects, never()).transition(anyLong(), any(), anyString(), anyString(),
                anyString(), any(Transition.class));
        verify(commands, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(classifications, relations, audit);
    }

    @Test
    void forwardsRelationReviewReasonAndPrefixedIdempotencyKey() throws Exception {
        Guide guide = guide(relationStep("REVIEW_REQUIRED", "REVIEW"));
        Command command = command(guide, "RELATION", "APPROVE", "独立审核结论", "command-1", null);
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);
        when(relations.reviews(USER, ROLES, BUILDING)).thenReturn(List.of(pendingReview()));
        when(relations.versionDetail(USER, ROLES, BUILDING, RELATION)).thenReturn(relationDetail());

        Receipt receipt = service.execute(USER, ROLES, BUILDING, PROJECT, command);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ReviewDecisionRequest> request = ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(relations).approve(eq(USER), eq(ROLES), eq("REVIEW-1"), key.capture(), request.capture());
        assertThat(key.getValue()).isEqualTo("work-order:command-1");
        assertThat(request.getValue().reason()).isEqualTo("独立审核结论");
        assertThat(receipt).isEqualTo(new Receipt(BUILDING, PROJECT, "RELATION", "APPROVE", RELATION,
                "EFFECTIVE", 9L));
    }

    @Test
    void forwardsExplicitProjectActivationParameters() throws Exception {
        Guide guide = guide(projectStep("ACTIVATION_REQUIRED", "ACTIVATE"));
        Instant effectiveFrom = Instant.parse("2026-10-20T01:02:03Z");
        Command command = command(guide, "PROJECT", "ACTIVATE", "批准后生效", "command-1", effectiveFrom);
        arrangeProject(USER);
        when(guides.guide(USER, ROLES, BUILDING, PROJECT)).thenReturn(guide);
        when(projects.activate(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), any(Transition.class)))
                .thenReturn(project("ACTIVE", 8L));

        service.execute(USER, ROLES, BUILDING, PROJECT, command);

        ArgumentCaptor<Transition> transition = ArgumentCaptor.forClass(Transition.class);
        verify(projects).activate(eq(USER), eq(ROLES), eq(BUILDING), eq(PROJECT), transition.capture());
        assertThat(transition.getValue().expectedRevision()).isEqualTo(7L);
        assertThat(transition.getValue().reason()).isEqualTo("批准后生效");
        assertThat(transition.getValue().effectiveFrom()).isEqualTo(effectiveFrom);
        verify(projects, never()).transition(anyLong(), any(), anyString(), anyString(),
                anyString(), any(Transition.class));
    }

    private void arrangeProject(long user) {
        when(projects.versions(user, ROLES, BUILDING)).thenReturn(List.of(project("DRAFT", 7L)));
    }

    private static Version project(String status, long revision) {
        return new Version(PROJECT, BUILDING, 1, revision, status, null, null, USER, null,
                new Configuration(null, List.of(), List.of()));
    }

    private static Guide guide(Step step) {
        return new Guide(BUILDING, PROJECT, 7L, "NOT_CHECKED", List.of(step));
    }

    private static Step projectStep(String state, String action) {
        return new Step("PROJECT", PROJECT, 7L, state, "ENERGY_MANAGER", action, List.of());
    }

    private static Step relationStep(String state, String action) {
        return new Step("RELATION", RELATION, 9L, state, "PLATFORM_ADMIN", action, List.of());
    }

    private Command command(Guide guide, String domain, String action, String reason, String key,
                            Instant effectiveFrom) throws Exception {
        return new Command(fingerprint(guide), domain, action, reason, effectiveFrom, key);
    }

    private String fingerprint(Guide guide) throws Exception {
        return EnergyPeriodCalculationService.digest(mapper.writeValueAsString(guide));
    }

    private String hash(Command command) throws Exception {
        return EnergyPeriodCalculationService.digest(mapper.writeValueAsString(command));
    }

    private static ReviewView pendingReview() {
        return new ReviewView("REVIEW-1", RELATION, BUILDING, 1, "PENDING", 9L, "snapshot", USER,
                null, null, false, LocalDateTime.of(2026, 10, 20, 0, 0), null);
    }

    private static VersionDetailView relationDetail() {
        VersionView version = new VersionView(RELATION, "MODEL-1", BUILDING, 1, null, null, "EFFECTIVE",
                9L, 9L, "snapshot", "reason", USER, USER, OTHER_USER, OTHER_USER,
                LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0));
        return new VersionDetailView(null, version, null, List.of());
    }
}
