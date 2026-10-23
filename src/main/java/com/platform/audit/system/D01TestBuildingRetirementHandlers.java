package com.platform.audit.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.hvac.asset.service.D01TestBuildingRetirementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

record D01BuildingRetirementCommand(String buildingId, String expectedBuildingName, String evidenceSha256) {
    static D01BuildingRetirementCommand normalized(SystemSensitiveCommandSupport support, JsonNode json) {
        var value = support.read(json, D01BuildingRetirementCommand.class, "D01 建筑归档命令无效");
        String id = SystemSensitiveCommandSupport.requireText(value.buildingId(), 32, "D01 建筑归档命令无效");
        String name = SystemSensitiveCommandSupport.requireText(value.expectedBuildingName(), 100, "D01 建筑归档命令无效");
        String hash = SystemSensitiveCommandSupport.requireText(value.evidenceSha256(), 64, "D01 建筑归档命令无效");
        if (!hash.matches("[0-9a-f]{64}")) throw SystemSensitiveCommandSupport.invalid("D01 证据摘要无效");
        return new D01BuildingRetirementCommand(id, name, hash);
    }

    NormalizedSensitiveCommand freeze(SystemSensitiveCommandSupport support) {
        return new NormalizedSensitiveCommand(buildingId, "D01_TEST_BUILDING", buildingId,
                support.canonical(this, "D01 建筑归档命令无效"),
                "buildingId=" + buildingId + ";buildingName=" + expectedBuildingName.replaceAll("[;=\\r\\n]+", " ")
                        + ";evidenceSha256=" + evidenceSha256);
    }
}

@Component
@RequiredArgsConstructor
/** 第一步：审核后排除 D01 合成建筑的常规目录和全量建筑计数。 */
class ExcludeD01TestBuildingHandler implements SensitiveOperationHandler {
    private final SystemSensitiveCommandSupport support;
    private final D01TestBuildingRetirementService service;

    @Override public String operationCode() { return "EXCLUDE_D01_TEST_BUILDING"; }

    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return D01BuildingRetirementCommand.normalized(support, command).freeze(support);
    }

    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        var value = support.readCanonical(command.canonicalJson(), D01BuildingRetirementCommand.class, "D01 建筑归档命令无效");
        service.exclude(value.buildingId(), value.expectedBuildingName(), value.evidenceSha256(), context.requestId());
        return SensitiveOperationResult.none();
    }
}

@Component
@RequiredArgsConstructor
/** 第二步：复核同一快照后归档，仅改变目录状态，不删除建筑及关联事实。 */
class ArchiveD01TestBuildingHandler implements SensitiveOperationHandler {
    private final SystemSensitiveCommandSupport support;
    private final D01TestBuildingRetirementService service;

    @Override public String operationCode() { return "ARCHIVE_D01_TEST_BUILDING"; }

    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return D01BuildingRetirementCommand.normalized(support, command).freeze(support);
    }

    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        var value = support.readCanonical(command.canonicalJson(), D01BuildingRetirementCommand.class, "D01 建筑归档命令无效");
        service.archive(value.buildingId(), value.expectedBuildingName(), value.evidenceSha256(), context.requestId());
        return SensitiveOperationResult.none();
    }
}
