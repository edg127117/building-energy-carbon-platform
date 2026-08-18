package com.platform.hvac.asset.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 建筑、空间、系统、设备和测点管理 API 的稳定契约。 */
public final class AssetManagementContracts {
    private AssetManagementContracts() {
    }

    public record References(
            long spaces,
            long systemGroups,
            long equipment,
            long points,
            long authorizations,
            long children,
            long aliases,
            long identities) {
    }

    public record BuildingCreateRequest(
            @NotBlank @Size(max = 100) String buildingName,
            @Size(max = 50) String buildingCode,
            @Size(max = 30) String buildingType,
            Integer constructionYear,
            BigDecimal totalGfa,
            @Size(max = 30) String climateZone,
            String status) {
    }

    public record BuildingUpdateRequest(
            @NotBlank @Size(max = 100) String buildingName,
            @Size(max = 50) String buildingCode,
            @Size(max = 30) String buildingType,
            Integer constructionYear,
            BigDecimal totalGfa,
            @Size(max = 30) String climateZone,
            String status) {
    }

    @Schema(description = "建筑档案视图")
    public record BuildingView(
            String buildingId,
            String buildingName,
            String buildingCode,
            String buildingType,
            Integer constructionYear,
            BigDecimal totalGfa,
            String climateZone,
            String status,
            References references,
            List<String> allowedActions,
            long updateTime) {
    }

    public record SpaceCreateRequest(
            @NotBlank String buildingId,
            String parentSpaceId,
            @NotBlank @Size(max = 100) String spaceName,
            @Size(max = 50) String spaceCode,
            @Size(max = 20) String spaceType,
            Integer sortOrder,
            BigDecimal usableArea,
            String status) {
    }

    public record SpaceUpdateRequest(
            String parentSpaceId,
            @NotBlank @Size(max = 100) String spaceName,
            @Size(max = 50) String spaceCode,
            @Size(max = 20) String spaceType,
            Integer sortOrder,
            BigDecimal usableArea,
            String status) {
    }

    @Schema(description = "空间树节点")
    public record SpaceView(
            String spaceId,
            String buildingId,
            String parentSpaceId,
            String spaceName,
            String spaceCode,
            String spaceType,
            int sortOrder,
            BigDecimal usableArea,
            String status,
            References references,
            List<String> allowedActions,
            List<SpaceView> children,
            long updateTime) {
    }

    public record SystemGroupCreateRequest(
            @NotBlank String buildingId,
            @Size(max = 50) String systemCode,
            @NotBlank @Size(max = 100) String systemName,
            @Size(max = 30) String systemType,
            String status) {
    }

    public record SystemGroupUpdateRequest(
            @NotBlank @Size(max = 100) String systemName,
            @Size(max = 30) String systemType,
            String status) {
    }

    @Schema(description = "建筑内系统分组")
    public record SystemGroupView(
            String systemGroupId,
            String buildingId,
            String systemCode,
            String systemName,
            String systemType,
            int sortOrder,
            String status,
            References references,
            List<String> allowedActions,
            long updateTime) {
    }

    public record EquipmentCreateRequest(
            @NotBlank String buildingId,
            @NotBlank String spaceId,
            @NotBlank String systemGroupId,
            @NotBlank String typeCode,
            @NotBlank @Size(max = 100) String equipmentName,
            String productId,
            @Size(max = 100) String manufacturer,
            BigDecimal ratedCapacity,
            BigDecimal ratedPower,
            BigDecimal designCop,
            String status) {
    }

    public record EquipmentUpdateRequest(
            @NotBlank String buildingId,
            @NotBlank String spaceId,
            @NotBlank String systemGroupId,
            @NotBlank @Size(max = 100) String equipmentName,
            @Size(max = 100) String manufacturer,
            BigDecimal ratedCapacity,
            BigDecimal ratedPower,
            BigDecimal designCop,
            String status) {
    }

    public record IdentityView(
            String identityId,
            String identityType,
            String identityValue,
            String status,
            String expectedProfileCode) {
    }

    public record PointSummary(long total, long required, long configuredRequired) {
    }

    @Schema(description = "设备列表项")
    public record EquipmentListItemView(
            String equipmentId,
            String equipmentCode,
            String equipmentName,
            String typeCode,
            String category,
            String buildingId,
            String buildingName,
            String spaceId,
            String spaceName,
            String systemGroupId,
            String systemGroupName,
            String productId,
            String productName,
            String status,
            String expectedProfileCode,
            Long lastDiscoveredTime,
            PointSummary pointSummary,
            List<String> allowedActions,
            long updateTime) {
    }

    @Schema(description = "设备详情")
    public record EquipmentDetailView(
            String equipmentId,
            String equipmentCode,
            String equipmentName,
            String typeCode,
            String category,
            String buildingId,
            String buildingName,
            String spaceId,
            String spaceName,
            String systemGroupId,
            String systemGroupName,
            String productId,
            String productName,
            String manufacturer,
            BigDecimal ratedCapacity,
            BigDecimal ratedPower,
            BigDecimal designCop,
            String status,
            String expectedProfileCode,
            Long lastDiscoveredTime,
            List<IdentityView> identities,
            PointSummary pointSummary,
            References references,
            List<String> allowedActions,
            long updateTime) {
    }

    public record PointUpdateRequest(
            @Size(max = 100) String pointName,
            BigDecimal minValue,
            BigDecimal maxValue,
            Boolean forCalculation,
            @Size(max = 20) String status) {
    }

    @Schema(description = "设备测点视图")
    public record PointView(
            String pointId,
            String equipmentId,
            String pointCode,
            String pointName,
            String dataType,
            String unit,
            BigDecimal minValue,
            BigDecimal maxValue,
            boolean forCalculation,
            boolean required,
            String status,
            List<String> sourceAliases,
            References references,
            List<String> allowedActions,
            long updateTime) {
    }
}
