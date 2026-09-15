package com.platform.iot.protocol.api;

import com.platform.adapter.publication.ProtocolSnapshotContracts.*;
import com.platform.framework.common.Result;
import com.platform.iot.protocol.ProtocolPublicationService;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1/adapter-configurations/{targetId}")
@Tag(name="适配器配置同步")
/** 专用安全链以目标密钥认证，仅允许读取自己的快照和提交自己的加载回执。 */
public class AdapterConfigurationController {
    private final ProtocolPublicationService service;
    public AdapterConfigurationController(ProtocolPublicationService service){this.service=service;}
    @GetMapping @Operation(summary="拉取目标批准快照并报告解析能力")
    public Result<Envelope> pull(@PathVariable String targetId,@RequestHeader("X-Adapter-Schema-Version") int schema,
            @RequestHeader("X-Adapter-Output-Version") String output){return Result.success(service.pull(targetId,schema,output));}
    @PostMapping("/receipts") @Operation(summary="上报对应序号及摘要的加载结果")
    public Result<Void> receipt(@PathVariable String targetId,@RequestBody Receipt receipt){service.receipt(targetId,receipt);return Result.success(null);}
}
