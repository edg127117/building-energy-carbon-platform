package com.platform.relation;

import jakarta.validation.constraints.*;
import java.util.List;

/** 新档案的草稿输入；不接收客户端内部身份、在线数据或未经确认的计量推断。 */
public final class RelationStagingContracts {
    private RelationStagingContracts() { }
    public record EquipmentRequest(@NotNull @Min(0) Long expectedRevision,
                                   @NotBlank @Size(max=500) String reason,
                                   @NotBlank @Size(max=100) String name,
                                   @NotBlank String typeCode, String productId,
                                   @Size(max=100) String manufacturer,
                                   String spaceId, String systemGroupId) { }
    public record PointRequest(@NotNull @Min(0) Long expectedRevision,
                               @NotBlank @Size(max=500) String reason,
                               @NotBlank @Size(max=100) String name,
                               @NotBlank String namingRuleId, String pointCode,
                               String suffixCode, String equipmentId, String systemGroupId,
                               @NotBlank String dataType, String unit,
                               @NotNull @Min(0) @Max(1) Integer isForCalc,
                               @NotBlank String status) { }
    public record RemoveRequest(@NotNull @Min(0) Long expectedRevision,
                                @NotBlank @Size(max=500) String reason) { }
    public record Asset(String stagedId,String objectType,String objectId,String nodeId,
                        String code,String name,String status,String definitionJson,
                        String spaceId,String systemGroupId,String equipmentId) { }
    public record Mutation(String versionId,long revision,List<Asset> assets) { }
    public record Option(String id,String name,String code) { }
    public record Options(List<Option> equipmentTypes,List<Option> products,List<Option> namingRules) { }
    public record EquipmentDefinition(String typeCode,String category,String productId,String manufacturer,
                                      String typeFingerprint,String productFingerprint) { }
    public record PointDefinition(String namingRuleId,String familyCode,String componentCode,String suffixCode,
                                  String dataType,String unit,int isForCalc,String status,String ruleFingerprint) { }
}
