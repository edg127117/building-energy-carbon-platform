package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeStatus;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.DraftPublishRequest;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.DraftReference;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.FrozenCommand;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.PublishRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 将草稿冻结和送审作为一次提交，运行配置仍由原审批处理器发布。 */
@Service
public class ProtocolDraftPublicationService {
    private static final String OPERATION_CODE = "PUBLISH_PROTOCOL_CONFIGURATION";

    private final ProtocolDraftService drafts;
    private final ProtocolPublicationService publication;
    private final SensitiveChangeService changes;
    private final ObjectMapper mapper;

    public ProtocolDraftPublicationService(ProtocolDraftService drafts, ProtocolPublicationService publication,
                                           SensitiveChangeService changes, ObjectMapper mapper) {
        this.drafts = drafts;
        this.publication = publication;
        this.changes = changes;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SensitiveChangeRecord submit(long userId, Set<String> roles, DraftPublishRequest request) {
        ProtocolDraftService.requireAdmin(roles);
        var reference = new DraftReference(request.draftId(), request.revision());
        var existing = changes.findOwnRequestForSubmission(userId, request.idempotencyKey());
        if (existing.isPresent()) return resume(userId, request, reference, existing.get());

        var draft = drafts.detailForUpdate(request.draftId(), roles);
        // 等待草稿行锁期间，相同请求可能已完成送审。重查后再校验实时发布序号。
        existing = changes.findOwnRequestForSubmission(userId, request.idempotencyKey());
        if (existing.isPresent()) return resume(userId, request, reference, existing.get());

        var version = publication.freezeLockedDraft(draft, request.revision(), userId, roles);
        var command = publication.prepare(new PublishRequest(request.targetId(), request.expectedSequence(),
                List.of(version.versionId()), request.idempotencyKey()), roles).withDraftReference(reference);
        var approval = changes.createDraft(userId, OPERATION_CODE, mapper.valueToTree(command), request.idempotencyKey());
        return submitIfDraft(userId, approval);
    }

    private SensitiveChangeRecord resume(long userId, DraftPublishRequest request, DraftReference reference,
                                         SensitiveChangeRecord existing) {
        if (!OPERATION_CODE.equals(existing.operationCode())) throw idempotencyConflict();
        var command = publication.read(existing.commandJson(), FrozenCommand.class);
        if (!reference.equals(command.draftReference()) || !request.targetId().equals(command.targetId())
                || request.expectedSequence() != command.expectedSequence()) {
            throw idempotencyConflict();
        }
        return submitIfDraft(userId, existing);
    }

    private SensitiveChangeRecord submitIfDraft(long userId, SensitiveChangeRecord approval) {
        return approval.status() == SensitiveChangeStatus.DRAFT ? changes.submit(userId, approval.requestId()) : approval;
    }

    private static BusinessException idempotencyConflict() {
        return AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "幂等键请求内容不一致");
    }
}
