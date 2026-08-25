package com.platform.iot.deviceparameter.api;

import com.platform.framework.common.Result;
import com.platform.iot.deviceparameter.DeviceParameterAuthorization;
import com.platform.iot.deviceparameter.DeviceParameterCatalogService;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.MappingVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterTemplateService;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.TemplateRevision;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyMapping;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.*;

@Tag(name = "标准设备参数目录与映射")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "定义、单位、边界或映射校验失败",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class))),
        @ApiResponse(responseCode = "403", description = "全局配置权限不足",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class))),
        @ApiResponse(responseCode = "409", description = "编码、状态或修订冲突",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class)))
})
/** HTTP 只适配全局目录 DTO；状态、引用冻结和审计由 Catalog Service 强制执行。 */
public class DeviceParameterCatalogController {
    private final DeviceParameterCatalogService service;
    private final DeviceParameterAuthorization authorization;
    private final DeviceParameterTemplateService templateService;

    @PostMapping("/v1/device-parameter-units")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> createUnit(
            Authentication authentication, @Valid @RequestBody UnitRequest request) {
        service.createUnit(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success();
    }

    @PutMapping("/v1/device-parameter-units/{unitCode}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> updateUnit(
            Authentication authentication,
            @PathVariable String unitCode,
            @RequestParam String status,
            @Valid @RequestBody UnitRequest request) {
        service.updateUnit(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                unitCode, request, status);
        return Result.success();
    }

    @PostMapping("/v1/device-parameter-definitions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<Definition>> createDefinition(
            Authentication authentication, @Valid @RequestBody DefinitionRequest request) {
        Definition value = service.createDefinition(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "ENABLE", "DISABLE")));
    }

    @PutMapping("/v1/device-parameter-definitions/{definitionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<Definition>> updateDefinition(
            Authentication authentication,
            @PathVariable String definitionId,
            @RequestParam String status,
            @Valid @RequestBody DefinitionRequest request) {
        Definition value = service.updateDefinition(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), definitionId, request, status);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "ENABLE", "DISABLE")));
    }

    @GetMapping("/v1/device-parameter-definitions")
    public Result<List<AllowedView<Definition>>> listDefinitions(Authentication authentication) {
        List<String> actions = authorization.isGlobalAdministrator(SecurityUser.roles(authentication))
                ? List.of("UPDATE", "ENABLE", "DISABLE") : List.of();
        return Result.success(service.listDefinitions(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication)).stream()
                .map(value -> new AllowedView<>(value, actions)).toList());
    }

    @PostMapping("/v1/device-parameter-applicabilities")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<Applicability>> saveApplicability(
            Authentication authentication,
            @RequestParam String status,
            @Valid @RequestBody ApplicabilityRequest request) {
        Applicability value = service.saveApplicability(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request, status);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "ENABLE", "DISABLE")));
    }

    @PostMapping("/v1/device-parameter-mappings/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<MappingVersion>> createMappingDraft(
            Authentication authentication, @Valid @RequestBody MappingDraftRequest request) {
        MappingVersion value = service.createMappingDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success(new AllowedView<>(value, List.of("PUBLISH")));
    }

    @PostMapping("/v1/device-parameter-mappings/versions/{mappingVersionId}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<MappingVersion>> publishMapping(
            Authentication authentication,
            @PathVariable String mappingVersionId,
            @RequestParam int expectedRevision) {
        MappingVersion value = service.publishMapping(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), mappingVersionId, expectedRevision);
        return Result.success(new AllowedView<>(value, List.of("COPY")));
    }

    @GetMapping("/v1/device-parameter-mappings/versions/{mappingVersionId}")
    public Result<List<AllowedView<MappingVersion>>> listMappingVersions(
            Authentication authentication, @PathVariable String mappingVersionId) {
        List<String> actions = authorization.isGlobalAdministrator(SecurityUser.roles(authentication))
                ? List.of("ROLLBACK") : List.of();
        return Result.success(service.listMappingVersions(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication), mappingVersionId).stream()
                .map(value -> new AllowedView<>(value, actions)).toList());
    }

    @PostMapping("/v1/device-parameter-mappings/rollback-drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<MappingVersion>> createMappingRollbackDraft(
            Authentication authentication, @Valid @RequestBody MappingRollbackRequest request) {
        MappingVersion value = service.createMappingRollbackDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success(new AllowedView<>(value, List.of("PUBLISH")));
    }

    @PostMapping("/v1/device-parameter-templates/revisions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<TemplateRevision>> createTemplateDraft(
            Authentication authentication,
            @Valid @RequestBody TemplateRevisionRequest request) {
        TemplateRevision value = templateService.createDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success(new AllowedView<>(value, List.of("PUBLISH")));
    }

    @PostMapping("/v1/device-parameter-templates/revisions/{revisionId}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<TemplateRevision>> publishTemplate(
            Authentication authentication,
            @PathVariable String revisionId,
            @RequestParam int expectedRevision) {
        TemplateRevision value = templateService.publish(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), revisionId, expectedRevision);
        return Result.success(new AllowedView<>(value, List.of("APPLY")));
    }

    @PostMapping("/v1/device-parameter-templates/equipment/{equipmentId}/apply")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<AllowedView<Candidate>>> applyTemplate(
            Authentication authentication, @PathVariable String equipmentId) {
        return Result.success(templateService.apply(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication), equipmentId).stream()
                .map(value -> new AllowedView<>(value, List.of("SELECT_FOR_DRAFT"))).toList());
    }

    @PostMapping("/v1/device-parameter-legacy-mappings")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<AllowedView<LegacyMapping>> saveLegacyMapping(
            Authentication authentication,
            @RequestParam String status,
            @Valid @RequestBody LegacyMappingRequest request) {
        LegacyMapping value = service.saveLegacyMapping(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request, status);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "ENABLE", "DISABLE")));
    }
}
