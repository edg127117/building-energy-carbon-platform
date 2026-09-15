package com.platform.iot.protocol.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.protocol.*;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.platform.iot.protocol.api.ProtocolContracts.*;

@RestController
@RequestMapping("/v1/protocol-configurations")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name="协议配置草稿")
@SecurityRequirement(name="bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode="400",description="配置不合法或样例超限"),
    @ApiResponse(responseCode="403",description="非平台管理员"),
    @ApiResponse(responseCode="404",description="草稿不存在"),
    @ApiResponse(responseCode="409",description="草稿修订冲突"),
    @ApiResponse(responseCode="429",description="样例检查或预览过于频繁")
})
/** 管理员专用草稿及预览入口；没有启用、发布或设备绑定的隐式副作用。 */
public class ProtocolConfigurationController {
    private final ProtocolDraftService drafts;
    private final ProtocolPreviewService previews;
    private final ProtocolPreviewLimiter limiter;
    public ProtocolConfigurationController(ProtocolDraftService drafts,ProtocolPreviewService previews,ProtocolPreviewLimiter limiter) {
        this.drafts=drafts; this.previews=previews; this.limiter=limiter;
    }
    @GetMapping
    @Operation(summary="分页读取尚未生效的协议草稿")
    public Result<PageResponse<Detail>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,Authentication auth) {
        return Result.success(drafts.list(page,size,SecurityUser.roles(auth)));
    }
    @GetMapping("/{id}")
    @Operation(summary="读取协议草稿")
    public Result<Detail> detail(@PathVariable String id,Authentication auth) {
        return Result.success(drafts.detail(id,SecurityUser.roles(auth)));
    }
    @PostMapping
    @Operation(summary="保存协议草稿，不启用解析规则")
    public Result<Detail> create(@Valid @RequestBody Configuration config,Authentication auth) {
        return Result.success(drafts.create(config,SecurityUser.userId(auth),SecurityUser.roles(auth)));
    }
    @PutMapping("/{id}")
    @Operation(summary="按预期修订号更新协议草稿")
    public Result<Detail> update(@PathVariable String id,@Valid @RequestBody UpdateRequest request,Authentication auth) {
        return Result.success(drafts.update(id,request,SecurityUser.userId(auth),SecurityUser.roles(auth)));
    }
    @PostMapping("/inspect")
    @Operation(summary="检查临时 JSON 样例并返回可选择字段")
    public Result<Inspection> inspect(@Valid @RequestBody InspectRequest request,Authentication auth) {
        limiter.acquire(SecurityUser.userId(auth));
        return Result.success(previews.inspect(request.samplePayload(),SecurityUser.roles(auth)));
    }
    @PostMapping("/preview")
    @Operation(summary="无写解析预览；结果成功不代表运行配置已发布")
    public Result<Preview> preview(@Valid @RequestBody PreviewRequest request,Authentication auth) {
        limiter.acquire(SecurityUser.userId(auth));
        return Result.success(previews.preview(request,SecurityUser.roles(auth)));
    }
}
