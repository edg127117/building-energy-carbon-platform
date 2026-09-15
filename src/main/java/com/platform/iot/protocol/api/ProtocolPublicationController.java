package com.platform.iot.protocol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.framework.common.Result;
import com.platform.iot.protocol.ProtocolPublicationService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.iot.protocol.api.ProtocolPublicationContracts.*;

@RestController
@RequestMapping("/v1/protocol-deployments")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name="协议发布")
/** 发布入口创建公共审批申请；目标凭据仅首次登记返回，状态接口不返回凭据摘要。 */
public class ProtocolPublicationController {
    private final ProtocolPublicationService service;
    private final SensitiveChangeService changes;
    private final ObjectMapper mapper;
    public ProtocolPublicationController(ProtocolPublicationService service,SensitiveChangeService changes,ObjectMapper mapper) {
        this.service=service;this.changes=changes;this.mapper=mapper;
    }
    @GetMapping("/targets") @Operation(summary="读取适配器目标及联系状态")
    public Result<List<TargetView>> targets(Authentication auth){return Result.success(service.targets(SecurityUser.roles(auth)));}
    @PostMapping("/targets") @Operation(summary="登记精确Topic授权目标并一次返回独立密钥")
    public Result<TargetCreated> register(@Valid @RequestBody TargetRequest request,Authentication auth){return Result.success(service.register(request,SecurityUser.userId(auth),SecurityUser.roles(auth)));}
    @PostMapping("/versions/{draftId}") @Operation(summary="冻结已保存协议与批准产品契约")
    public Result<VersionView> freeze(@PathVariable String draftId,@Valid @RequestBody FreezeRequest request,Authentication auth){return Result.success(service.freeze(draftId,request.revision(),SecurityUser.userId(auth),SecurityUser.roles(auth)));}
    @GetMapping("/versions") @Operation(summary="读取不可变协议版本")
    public Result<List<VersionView>> versions(Authentication auth){return Result.success(service.versions(SecurityUser.roles(auth)));}
    @PostMapping("/import") @Operation(summary="迁入历史完整协议与禁用归档，不发布")
    public Result<List<VersionView>> importLegacy(@Valid @RequestBody ImportRequest request,Authentication auth){return Result.success(service.importLegacy(request,SecurityUser.userId(auth),SecurityUser.roles(auth)));}
    @GetMapping("/targets/{targetId}/history") @Operation(summary="读取目标发布与加载记录")
    public Result<List<DeploymentView>> history(@PathVariable String targetId,Authentication auth){return Result.success(service.deployments(targetId,SecurityUser.roles(auth)));}
    @PostMapping("/requests") @Operation(summary="为完整目标集合建立审批申请")
    public Result<SensitiveChangeRecord> publish(@Valid @RequestBody PublishRequest request,Authentication auth){
        var command=service.prepare(request,SecurityUser.roles(auth));
        return Result.success(changes.createDraft(SecurityUser.userId(auth),"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(command),request.idempotencyKey()));
    }
    @PostMapping("/rollback-requests") @Operation(summary="以新发布序号申请回退至历史已加载配置")
    public Result<SensitiveChangeRecord> rollback(@Valid @RequestBody RollbackRequest request,Authentication auth){
        var command=service.rollback(request,SecurityUser.roles(auth));
        return Result.success(changes.createDraft(SecurityUser.userId(auth),"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(command),request.idempotencyKey()));
    }
}
