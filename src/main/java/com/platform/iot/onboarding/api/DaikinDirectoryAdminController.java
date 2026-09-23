package com.platform.iot.onboarding.api;

import com.platform.framework.common.Result;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.daikin.onboarding.DaikinPendingLocationService;
import com.platform.iot.daikin.onboarding.DaikinPendingLocationService.LocationInput;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/daikin/directory")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "大金目录配置")
@RequiredArgsConstructor
/** 来源登记只建立不可变逻辑身份，不接受凭据、任意 URL 或浏览器上传的厂家数据。 */
public class DaikinDirectoryAdminController {
    private final DaikinDirectoryService service;
    private final DaikinPendingLocationService locations;

    @Operation(summary = "登记逻辑来源；不启用外部采集")
    @PostMapping("/sources")
    public Result<Void> source(Authentication authentication, @Valid @RequestBody SourceRequest request) {
        service.registerSource(request.sourceId(), request.sourceName(), SecurityUser.userId(authentication),
                SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "确认厂家项目的真实建筑映射；已绑定项目不允许原地改归属")
    @PutMapping("/projects/building")
    public Result<Void> mapProject(Authentication authentication, @Valid @RequestBody ProjectRequest request) {
        service.mapProject(request.sourceId(), request.siteId(), request.buildingId(),
                SecurityUser.userId(authentication), SecurityUser.roles(authentication));
        return Result.success();
    }

    @Operation(summary = "登记已核对的待接入内机位置；不创建正式设备或改变厂家身份")
    @PutMapping("/pending-locations")
    public Result<Void> pendingLocations(Authentication authentication,
                                         @Valid @RequestBody PendingLocationsRequest request) {
        locations.mapIndoor(request.sourceId(), request.siteId(), request.items(),
                SecurityUser.userId(authentication), SecurityUser.roles(authentication));
        return Result.success();
    }

    @Schema(name = "DaikinDirectorySourceRequest")
    public record SourceRequest(@NotBlank @Size(max = 200) String sourceId,
                                @NotBlank @Size(max = 200) String sourceName) { }
    @Schema(name = "DaikinDirectoryProjectRequest")
    public record ProjectRequest(@NotBlank @Size(max = 200) String sourceId,
                                 @NotBlank @Size(max = 200) String siteId,
                                 @NotBlank @Size(max = 32) String buildingId) { }
    @Schema(name = "DaikinPendingLocationsRequest")
    public record PendingLocationsRequest(@NotBlank String sourceId, @NotBlank String siteId,
                                          @jakarta.validation.constraints.NotEmpty
                                          List<@NotNull LocationInput> items) { }
}
