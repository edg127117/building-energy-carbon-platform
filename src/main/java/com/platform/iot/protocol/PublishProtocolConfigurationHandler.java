package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.*;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.FrozenCommand;
import com.platform.security.SecurityUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 使用既有职责分离审批执行完整目标配置，通用申请入口同样必须通过平台管理员校验。 */
@Component
public class PublishProtocolConfigurationHandler implements SensitiveOperationHandler {
    private final ProtocolPublicationService service;
    public PublishProtocolConfigurationHandler(ProtocolPublicationService service) {this.service=service;}
    @Override public String operationCode(){return "PUBLISH_PROTOCOL_CONFIGURATION";}
    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        requireAdmin();
        var value=service.validateCommand(service.read(command.toString(),FrozenCommand.class));
        return new NormalizedSensitiveCommand(null,"PROTOCOL_TARGET",value.targetId(),service.json(value),
                "expectedSequence="+value.expectedSequence()+";digest="+value.digest());
    }
    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command,SensitiveOperationContext context) {
        requireAdmin();
        service.execute(service.read(command.canonicalJson(),FrozenCommand.class),context);
        return SensitiveOperationResult.none();
    }
    private void requireAdmin(){ProtocolDraftService.requireAdmin(SecurityUser.roles(SecurityContextHolder.getContext().getAuthentication()));}
}
