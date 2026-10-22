package com.platform.carbon;

import com.platform.energy.classification.ClassificationService;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.platform.carbon.CarbonProjectContracts.*;
import static com.platform.carbon.CarbonErrors.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarbonConfigurationGuideServiceTest {
    private final CarbonProjectService projects = mock(CarbonProjectService.class);
    private final ClassificationService classifications = mock(ClassificationService.class);
    private final RelationGovernanceService relations = mock(RelationGovernanceService.class);
    private final CarbonConfigurationGuideService service = new CarbonConfigurationGuideService(projects, classifications, relations);
    private final List<String> roles = List.of("ENERGY_MANAGER");

    private Version version(String status, long revision, String classification) {
        return new Version("P", "B", 1, revision, status, null, null, 1, null,
                new Configuration(classification, List.of(new Energy("CUSTOM", "PURCHASED_ELECTRICITY", "ENABLED")), List.of()));
    }

    @Test void exposesMissingStepsWithoutInventingFormalReadiness() {
        when(projects.versions(1, roles, "B")).thenReturn(List.of(version("DRAFT", 1, null)));
        when(projects.readiness(1, roles, "B", "P")).thenReturn(new Readiness("B", "P", false, List.of(new Issue("MISSING", null, "未配置"))));
        var guide = service.guide(1, roles, "B", "P");
        assertThat(guide.calculationStatus()).isEqualTo("NOT_CHECKED");
        assertThat(guide.steps()).extracting(CarbonConfigurationGuideService.Step::state)
                .containsExactly("MISSING", "MISSING", "BLOCKED", "NOT_CHECKED", "NOT_CHECKED");
        verifyNoInteractions(classifications, relations);
    }

    @Test void preservesIndependentDomainReviewAndDoesNotRunMutatingValidation() {
        when(projects.versions(1, roles, "B")).thenReturn(List.of(version("APPROVED", 3, "C")));
        when(projects.readiness(1, roles, "B", "P")).thenReturn(new Readiness("B", "P", true, List.of()));
        var configuration = new ClassificationContracts.Configuration("R", List.of(), List.of(), List.of(), List.of());
        when(classifications.version(1, roles, "B", "C")).thenReturn(new ClassificationContracts.VersionView("C", "B", 1, 5, "APPROVED", null, null, 1, 2L, configuration, List.of()));
        var relation = new RelationContracts.VersionView("R", "M", "B", 1, null, null, "SUBMITTED", 4, 4L, "hash", "reason", 1L, 1L, null, null, null, null);
        var metadata = new RelationContracts.QueryMetadata("B", "R", 1, null, 4, 0, false, 0, 0, 0, 0);
        when(relations.versionDetail(1L, roles, "B", "R")).thenReturn(new RelationContracts.VersionDetailView(metadata, relation, null, List.of()));
        var guide = service.guide(1, roles, "B", "P");
        assertThat(guide.steps().getFirst()).satisfies(step -> {
            assertThat(step.state()).isEqualTo("REVIEW_REQUIRED");
            assertThat(step.responsibility()).isEqualTo("PLATFORM_ADMIN");
            assertThat(step.revision()).isEqualTo(4);
        });
        assertThat(guide.steps().get(1).state()).isEqualTo("CONFIGURED");
        assertThat(guide.steps().get(2)).satisfies(step -> {
            assertThat(step.action()).isEqualTo("ACTIVATE");
            assertThat(step.responsibility()).isEqualTo("RULE_ACTIVATOR");
        });
        verify(relations, never()).validate(anyLong(), any(), anyString());
    }

    @Test void unauthorizedBuildingNeverReadsCrossDomainFacts() {
        when(projects.versions(1, roles, "B")).thenThrow(error(403, FORBIDDEN, "建筑未授权"));
        assertThatThrownBy(() -> service.guide(1, roles, "B", "P")).hasMessageContaining("未授权");
        verifyNoInteractions(classifications, relations);
    }

    @Test void missingDependenciesAreWorkItemsButPermissionErrorsStillPropagate() {
        when(projects.versions(1, roles, "B")).thenReturn(List.of(version("DRAFT", 1, "C")));
        when(projects.readiness(1, roles, "B", "P")).thenThrow(error(404, NOT_FOUND, "分类已失效"));
        when(classifications.version(1, roles, "B", "C")).thenThrow(error(404, NOT_FOUND, "分类已失效"));
        assertThat(service.guide(1, roles, "B", "P").steps().get(1).state()).isEqualTo("BLOCKED");
        doThrow(error(403, FORBIDDEN, "无权访问分类")).when(classifications).version(1, roles, "B", "C");
        assertThatThrownBy(() -> service.guide(1, roles, "B", "P")).hasMessageContaining("无权访问");
    }

    @Test void concurrentProjectRevisionInvalidatesTheWholeGuide() {
        when(projects.versions(1, roles, "B")).thenReturn(List.of(version("DRAFT", 1, null)), List.of(version("DRAFT", 2, null)));
        when(projects.readiness(1, roles, "B", "P")).thenReturn(new Readiness("B", "P", false, List.of()));
        assertThatThrownBy(() -> service.guide(1, roles, "B", "P")).hasMessageContaining("已变化");
    }

    @Test void buildingOwnerCanReadGuideWithoutObtainingRelationHistoryPermission() {
        var ownerRoles = List.of("BUILDING_OWNER");
        when(projects.versions(1, ownerRoles, "B")).thenReturn(List.of(version("DRAFT", 1, "C")));
        when(projects.readiness(1, ownerRoles, "B", "P")).thenReturn(new Readiness("B", "P", false, List.of()));
        var configuration = new ClassificationContracts.Configuration("R", List.of(), List.of(), List.of(), List.of());
        when(classifications.version(1, ownerRoles, "B", "C")).thenReturn(new ClassificationContracts.VersionView("C", "B", 1, 2, "DRAFT", null, null, 1, null, configuration, List.of()));
        var guide = service.guide(1, ownerRoles, "B", "P");
        assertThat(guide.steps().getFirst()).satisfies(step -> {
            assertThat(step.state()).isEqualTo("NOT_CHECKED");
            assertThat(step.issues()).extracting(Issue::code).containsExactly("RELATION_HISTORY_RESTRICTED");
        });
        verifyNoInteractions(relations);
    }

    @Test void changedClassificationDependencyInvalidatesGuideEvenWhenProjectRevisionIsUnchanged() {
        var ownerRoles = List.of("BUILDING_OWNER");
        when(projects.versions(1, ownerRoles, "B")).thenReturn(List.of(version("DRAFT", 1, "C")));
        when(projects.readiness(1, ownerRoles, "B", "P")).thenReturn(new Readiness("B", "P", false, List.of()));
        var oldConfiguration = new ClassificationContracts.Configuration("R1", List.of(), List.of(), List.of(), List.of());
        var newConfiguration = new ClassificationContracts.Configuration("R2", List.of(), List.of(), List.of(), List.of());
        when(classifications.version(1, ownerRoles, "B", "C")).thenReturn(
                new ClassificationContracts.VersionView("C", "B", 1, 1, "DRAFT", null, null, 1, null, oldConfiguration, List.of()),
                new ClassificationContracts.VersionView("C", "B", 1, 2, "DRAFT", null, null, 1, null, newConfiguration, List.of()));
        assertThatThrownBy(() -> service.guide(1, ownerRoles, "B", "P")).hasMessageContaining("已变化");
        verifyNoInteractions(relations);
    }

    @Test void changedRelationRevisionInvalidatesGuideWithoutATransactionAcrossDomains() {
        when(projects.versions(1, roles, "B")).thenReturn(List.of(version("APPROVED", 3, "C")));
        when(projects.readiness(1, roles, "B", "P")).thenReturn(new Readiness("B", "P", true, List.of()));
        var configuration = new ClassificationContracts.Configuration("R", List.of(), List.of(), List.of(), List.of());
        when(classifications.version(1, roles, "B", "C")).thenReturn(new ClassificationContracts.VersionView("C", "B", 1, 5, "APPROVED", null, null, 1, 2L, configuration, List.of()));
        var oldRelation = new RelationContracts.VersionView("R", "M", "B", 1, null, null, "DRAFT", 1, null, null, "reason", 1L, null, null, null, null, null);
        var newRelation = new RelationContracts.VersionView("R", "M", "B", 1, null, null, "DRAFT", 2, null, null, "reason", 1L, null, null, null, null, null);
        var metadata = new RelationContracts.QueryMetadata("B", "R", 1, null, 4, 0, false, 0, 0, 0, 0);
        when(relations.versionDetail(1L, roles, "B", "R")).thenReturn(
                new RelationContracts.VersionDetailView(metadata, oldRelation, null, List.of()),
                new RelationContracts.VersionDetailView(metadata, newRelation, null, List.of()));
        assertThatThrownBy(() -> service.guide(1, roles, "B", "P")).hasMessageContaining("已变化");
    }
}
