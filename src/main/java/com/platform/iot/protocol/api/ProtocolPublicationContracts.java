package com.platform.iot.protocol.api;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/** 发布管理只传递配置标识与冻结版本；适配器凭据不进入普通列表或审批内容。 */
public final class ProtocolPublicationContracts {
    private ProtocolPublicationContracts() {}

    public record TargetRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Pattern(regexp="V1|V2") String outputVersion,
            @NotEmpty @Size(max=100) List<@NotBlank @Size(max=200) String> allowedTopics) {}
    public record TargetView(String targetId,String name,String outputVersion,List<String> allowedTopics,
            long lastSeen,long currentSequence,String status,String errorCode) {}
    public record TargetCreated(TargetView target,String oneTimeKey) {}
    public record FreezeRequest(@Min(1) long revision) {}
    public record VersionView(String versionId,String draftId,long draftRevision,String digest,
            ProtocolContracts.Configuration configuration,long createdAt) {}
    public record PublishRequest(@NotBlank String targetId,@Min(0) long expectedSequence,
            @NotEmpty @Size(max=100) List<@NotBlank String> versionIds,@NotBlank @Size(max=100) String idempotencyKey) {}
    public record RollbackRequest(@NotBlank String targetId,@Min(1) long historicalSequence,
            @Min(0) long expectedSequence,@NotBlank @Size(max=100) String idempotencyKey) {}
    public record PreviewRequest(@NotBlank String targetId,@Min(0) long expectedSequence,
            @NotEmpty @Size(max=100) List<@NotBlank String> versionIds) {}
    public record RollbackPreviewRequest(@NotBlank String targetId,@Min(0) long expectedSequence,
            @Min(1) long historicalSequence) {}
    public enum ChangeType {ADDED,REPLACED,RETAINED,REMOVED}
    public record VersionSummary(String versionId,String name,String profileCode,long revision,int mappingCount) {}
    public record ProfileChange(String profileCode,ChangeType changeType,
            VersionSummary beforeVersion,VersionSummary afterVersion) {}
    public record PublicationPreview(String targetId,long expectedSequence,String digest,
            List<VersionSummary> currentVersions,List<VersionSummary> targetVersions,List<ProfileChange> changes) {}
    public record DeploymentView(String targetId,long sequence,String digest,String approvalId,
            String status,String errorCode,long createdAt,long loadedAt) {}
    public record DeploymentDetail(String targetId,long sequence,String digest,String approvalId,
            String status,String errorCode,long createdAt,long loadedAt,List<VersionSummary> versions) {}
    public record FrozenCommand(String targetId,long expectedSequence,String digest,String contentJson,
            List<String> versionIds,boolean rollback) {
        public FrozenCommand(String targetId,long expectedSequence,String digest,String contentJson,List<String> versionIds) {
            this(targetId,expectedSequence,digest,contentJson,versionIds,false);
        }
    }
    public record ImportRequest(@NotBlank @Size(max=1048576) String snapshotJson,
            @Size(max=1048576) String archiveJson,@NotEmpty @Size(max=100) Map<String,String> productBindings) {}
}
