package com.platform.hvac.asset.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.asset.service.AssetManagementService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.hvac.asset.api.AssetManagementContracts.*;

@Tag(name = "资产档案管理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/assets")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "请求或档案关系校验失败",
                content = @Content(schema = @Schema(implementation = AssetApiError.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
                content = @Content(schema = @Schema(implementation = AssetApiError.class))),
        @ApiResponse(responseCode = "403", description = "非平台管理员",
                content = @Content(schema = @Schema(implementation = AssetApiError.class))),
        @ApiResponse(responseCode = "404", description = "资产不存在",
                content = @Content(schema = @Schema(implementation = AssetApiError.class))),
        @ApiResponse(responseCode = "409", description = "引用或状态冲突",
                content = @Content(schema = @Schema(implementation = AssetApiError.class)))
})
/** 资产档案的版本化 HTTP 入口，只处理请求校验、身份提取和响应包装。 */
public class AssetManagementController {
    private final AssetManagementService service;

    @Operation(summary = "分页查询建筑")
    @GetMapping("/buildings")
    public Result<PageResponse<BuildingView>> listBuildings(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        return Result.success(service.listBuildings(
                page, size, keyword, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "查询建筑及引用计数")
    @GetMapping("/buildings/{buildingId}")
    public Result<BuildingView> buildingDetail(
            @PathVariable String buildingId, Authentication authentication) {
        return Result.success(service.buildingDetail(
                buildingId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "创建建筑")
    @PostMapping("/buildings")
    public Result<BuildingView> createBuilding(
            @Valid @RequestBody BuildingCreateRequest request, Authentication authentication) {
        return Result.success(service.createBuilding(request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新建筑")
    @PutMapping("/buildings/{buildingId}")
    public Result<BuildingView> updateBuilding(
            @PathVariable String buildingId,
            @Valid @RequestBody BuildingUpdateRequest request,
            Authentication authentication) {
        return Result.success(service.updateBuilding(
                buildingId, request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "无引用时逻辑删除建筑")
    @DeleteMapping("/buildings/{buildingId}")
    public Result<Void> deleteBuilding(
            @PathVariable String buildingId, Authentication authentication) {
        service.deleteBuilding(buildingId, SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "查询建筑空间树")
    @GetMapping("/spaces")
    public Result<List<SpaceView>> listSpaces(
            @RequestParam String buildingId, Authentication authentication) {
        return Result.success(service.listSpaces(buildingId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "创建空间")
    @PostMapping("/spaces")
    public Result<SpaceView> createSpace(
            @Valid @RequestBody SpaceCreateRequest request, Authentication authentication) {
        return Result.success(service.createSpace(request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新空间")
    @PutMapping("/spaces/{spaceId}")
    public Result<SpaceView> updateSpace(
            @PathVariable String spaceId,
            @Valid @RequestBody SpaceUpdateRequest request,
            Authentication authentication) {
        return Result.success(service.updateSpace(
                spaceId, request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "无子节点或设备引用时删除空间")
    @DeleteMapping("/spaces/{spaceId}")
    public Result<Void> deleteSpace(
            @PathVariable String spaceId, Authentication authentication) {
        service.deleteSpace(spaceId, SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "分页查询系统分组")
    @GetMapping("/system-groups")
    public Result<PageResponse<SystemGroupView>> listSystemGroups(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        return Result.success(service.listSystemGroups(
                page, size, buildingId, keyword, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "创建系统分组")
    @PostMapping("/system-groups")
    public Result<SystemGroupView> createSystemGroup(
            @Valid @RequestBody SystemGroupCreateRequest request, Authentication authentication) {
        return Result.success(service.createSystemGroup(request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新系统分组")
    @PutMapping("/system-groups/{systemGroupId}")
    public Result<SystemGroupView> updateSystemGroup(
            @PathVariable String systemGroupId,
            @Valid @RequestBody SystemGroupUpdateRequest request,
            Authentication authentication) {
        return Result.success(service.updateSystemGroup(
                systemGroupId, request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "无设备或测点引用时删除系统分组")
    @DeleteMapping("/system-groups/{systemGroupId}")
    public Result<Void> deleteSystemGroup(
            @PathVariable String systemGroupId, Authentication authentication) {
        service.deleteSystemGroup(systemGroupId, SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "按建筑、空间、系统、类型、产品和状态分页查询设备")
    @GetMapping("/equipment")
    public Result<PageResponse<EquipmentListItemView>> listEquipment(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String systemGroupId,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        return Result.success(service.listEquipment(page, size, buildingId, spaceId,
                systemGroupId, typeCode, productId, status, keyword,
                SecurityUser.roles(authentication)));
    }

    @Operation(summary = "查询设备身份、协议和测点完整度")
    @GetMapping("/equipment/{equipmentId}")
    public Result<EquipmentDetailView> equipmentDetail(
            @PathVariable String equipmentId, Authentication authentication) {
        return Result.success(service.equipmentDetail(
                equipmentId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "创建设备档案")
    @PostMapping("/equipment")
    public Result<EquipmentListItemView> createEquipment(
            @Valid @RequestBody EquipmentCreateRequest request, Authentication authentication) {
        return Result.success(service.createEquipment(request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新设备可编辑档案")
    @PutMapping("/equipment/{equipmentId}")
    public Result<EquipmentListItemView> updateEquipment(
            @PathVariable String equipmentId,
            @Valid @RequestBody EquipmentUpdateRequest request,
            Authentication authentication) {
        return Result.success(service.updateEquipment(
                equipmentId, request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "无测点或身份引用时删除设备")
    @DeleteMapping("/equipment/{equipmentId}")
    public Result<Void> deleteEquipment(
            @PathVariable String equipmentId, Authentication authentication) {
        service.deleteEquipment(equipmentId, SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "查询设备测点及来源别名")
    @GetMapping("/equipment/{equipmentId}/points")
    public Result<List<PointView>> listPoints(
            @PathVariable String equipmentId, Authentication authentication) {
        return Result.success(service.listPoints(
                equipmentId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新设备测点范围、计算标记和状态")
    @PutMapping("/equipment/{equipmentId}/points/{pointId}")
    public Result<PointView> updatePoint(
            @PathVariable String equipmentId,
            @PathVariable String pointId,
            @Valid @RequestBody PointUpdateRequest request,
            Authentication authentication) {
        return Result.success(service.updatePoint(
                equipmentId, pointId, request, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "无来源别名引用时删除设备测点")
    @DeleteMapping("/equipment/{equipmentId}/points/{pointId}")
    public Result<Void> deletePoint(
            @PathVariable String equipmentId,
            @PathVariable String pointId,
            Authentication authentication) {
        service.deletePoint(equipmentId, pointId, SecurityUser.roles(authentication));
        return Result.success();
    }
}
