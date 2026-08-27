package com.platform.audit.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.security.FormalRole;
import com.platform.system.service.SysUserAdminService;
import com.platform.system.service.PasswordSetupTokenService;
import com.platform.system.model.dto.UserAdminDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
/** 执行一次账号开通申请包，密码不进入命令，执行结果只返回一次激活令牌。 */
class OpenUserAccountHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "OPEN_USER_ACCOUNT";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "账号开通命令无效");
        String username = SystemSensitiveCommandSupport.requireText(value.username(), 50, "账号开通命令无效");
        if (username.length() < 3) throw SystemSensitiveCommandSupport.invalid("账号开通命令无效");
        List<String> roles = SystemSensitiveCommandSupport.strings(
                value.roleKeys(), true, true, FormalRole.values().length, 50, "账号开通命令无效");
        if (roles.stream().anyMatch(role -> !FormalRole.isFormal(role))) {
            throw SystemSensitiveCommandSupport.invalid("账号开通命令无效");
        }
        List<String> buildings = SystemSensitiveCommandSupport.strings(
                value.buildingIds(), false, false, 1000, 32, "账号开通命令无效");
        Command normalized = new Command(username,
                SystemSensitiveCommandSupport.optionalText(value.nickname(), 50, "账号开通命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.phone(), 20, "账号开通命令无效"),
                roles, buildings);
        return new NormalizedSensitiveCommand(buildings.size() == 1 ? buildings.getFirst() : null,
                "USER_ACCOUNT_OPENING", username, support.canonical(normalized, "账号开通命令无效"),
                "roleCount=" + roles.size() + ";buildingCount=" + buildings.size());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "账号开通命令无效");
        PasswordSetupTokenService.IssuedToken token = service.openAccount(
                new UserAdminDtos.OpenAccountRequest(value.username(), value.nickname(), value.phone(),
                        value.roleKeys(), value.buildingIds()), context.requestId(), context.reviewerId(),
                context.submitterId() == context.reviewerId());
        return SensitiveOperationResult.oneTimeToken(token.token(), token.purpose(), token.expiresAt());
    }

    private record Command(String username, String nickname, String phone,
                           List<String> roleKeys, List<String> buildingIds) {
    }
}

/** 执行已批准的凭据重置令牌签发，申请和审计都不包含新密码或原始令牌。 */
@Component
@RequiredArgsConstructor
class IssuePasswordResetTokenHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "ISSUE_PASSWORD_RESET_TOKEN";
    private final SystemSensitiveCommandSupport support;
    private final PasswordSetupTokenService tokenService;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        UserIdCommand value = support.read(command, UserIdCommand.class, "密码重置令牌命令无效");
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), "密码重置令牌命令无效");
        UserIdCommand normalized = new UserIdCommand(userId);
        return new NormalizedSensitiveCommand(null, "USER_CREDENTIAL", Long.toString(userId),
                support.canonical(normalized, "密码重置令牌命令无效"), "userId=" + userId);
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        UserIdCommand value = support.readCanonical(
                command.canonicalJson(), UserIdCommand.class, "密码重置令牌命令无效");
        PasswordSetupTokenService.IssuedToken token = tokenService.issue(
                value.userId(), context.requestId(), context.reviewerId());
        return SensitiveOperationResult.oneTimeToken(token.token(), token.purpose(), token.expiresAt());
    }
}

/** 执行已批准的逻辑删除，领域服务继续保护当前操作者和最后一个平台管理员。 */
@Component
@RequiredArgsConstructor
class DeleteUserAccountHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "DELETE_USER_ACCOUNT";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }
    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return UserCommandNormalization.userIdCommand(
                support, command, "账号删除命令无效", "USER_ACCOUNT");
    }
    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        UserIdCommand value = support.readCanonical(command.canonicalJson(), UserIdCommand.class, "账号删除命令无效");
        service.delete(context.reviewerId(), value.userId());
        return SensitiveOperationResult.none();
    }
}

/** 执行已批准的账号恢复；删除时已撤销的角色和建筑不会被隐式恢复。 */
@Component
@RequiredArgsConstructor
class RestoreUserAccountHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "RESTORE_USER_ACCOUNT";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }
    @Override public NormalizedSensitiveCommand normalize(JsonNode command) {
        return UserCommandNormalization.userIdCommand(
                support, command, "账号恢复命令无效", "USER_ACCOUNT");
    }
    @Override public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        UserIdCommand value = support.readCanonical(command.canonicalJson(), UserIdCommand.class, "账号恢复命令无效");
        service.restore(value.userId());
        return SensitiveOperationResult.none();
    }
}

record UserIdCommand(Long userId) {
}

final class UserCommandNormalization {
    private UserCommandNormalization() {
    }

    static NormalizedSensitiveCommand userIdCommand(SystemSensitiveCommandSupport support, JsonNode command,
                                                     String message, String targetType) {
        UserIdCommand value = support.read(command, UserIdCommand.class, message);
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), message);
        UserIdCommand normalized = new UserIdCommand(userId);
        return new NormalizedSensitiveCommand(null, targetType, Long.toString(userId),
                support.canonical(normalized, message), "userId=" + userId);
    }
}

/** 执行已批准的账号启停命令，领域服务继续负责自禁用和最后管理员保护。 */
@Component
@RequiredArgsConstructor
class UpdateUserStatusHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "UPDATE_USER_STATUS";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "账号状态变更命令无效");
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), "账号状态变更命令无效");
        int status = SystemSensitiveCommandSupport.flag(value.status(), -1, "账号状态变更命令无效");
        Command normalized = new Command(userId, status);
        return new NormalizedSensitiveCommand(null, "USER_ACCOUNT", Long.toString(userId),
                support.canonical(normalized, "账号状态变更命令无效"),
                "userId=" + userId + ";status=" + status);
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "账号状态变更命令无效");
        service.updateStatus(context.reviewerId(), value.userId(), value.status());
        return SensitiveOperationResult.none();
    }

    private record Command(Long userId, Integer status) {
    }
}

/** 执行已批准的正式角色全量替换，命令规范化阶段即拒绝四类角色之外的值。 */
@Component
@RequiredArgsConstructor
class ReplaceUserFormalRolesHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "REPLACE_USER_FORMAL_ROLES";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "正式角色变更命令无效");
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), "正式角色变更命令无效");
        List<String> roles = SystemSensitiveCommandSupport.strings(
                value.roleKeys(), true, true, FormalRole.values().length, 50, "正式角色变更命令无效");
        if (roles.stream().anyMatch(role -> !FormalRole.isFormal(role))) {
            throw SystemSensitiveCommandSupport.invalid("正式角色变更命令无效");
        }
        Command normalized = new Command(userId, roles);
        return new NormalizedSensitiveCommand(null, "USER_FORMAL_ROLE", Long.toString(userId),
                support.canonical(normalized, "正式角色变更命令无效"),
                "userId=" + userId + ";roleCount=" + roles.size());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "正式角色变更命令无效");
        service.replaceRoles(context.reviewerId(), value.userId(), value.roleKeys());
        return SensitiveOperationResult.none();
    }

    private record Command(Long userId, List<String> roleKeys) {
    }
}

/** 执行已批准的建筑范围全量替换，建筑存在性仍由领域事务在执行时复核。 */
@Component
@RequiredArgsConstructor
class ReplaceUserBuildingsHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "REPLACE_USER_BUILDINGS";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "建筑授权变更命令无效");
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), "建筑授权变更命令无效");
        List<String> buildings = SystemSensitiveCommandSupport.strings(
                value.buildingIds(), false, false, 1000, 32, "建筑授权变更命令无效");
        Command normalized = new Command(userId, buildings);
        String buildingId = buildings.size() == 1 ? buildings.getFirst() : null;
        return new NormalizedSensitiveCommand(buildingId, "USER_BUILDING_SCOPE", Long.toString(userId),
                support.canonical(normalized, "建筑授权变更命令无效"),
                "userId=" + userId + ";buildingCount=" + buildings.size());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "建筑授权变更命令无效");
        service.replaceBuildings(value.userId(), value.buildingIds());
        return SensitiveOperationResult.none();
    }

    private record Command(Long userId, List<String> buildingIds) {
    }
}

/** 执行已批准的单建筑撤权，避免全量替换命令扩大实际影响范围。 */
@Component
@RequiredArgsConstructor
class RevokeUserBuildingHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "REVOKE_USER_BUILDING";
    private final SystemSensitiveCommandSupport support;
    private final SysUserAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "建筑授权撤销命令无效");
        long userId = SystemSensitiveCommandSupport.requireId(value.userId(), "建筑授权撤销命令无效");
        String buildingId = SystemSensitiveCommandSupport.requireText(
                value.buildingId(), 32, "建筑授权撤销命令无效");
        Command normalized = new Command(userId, buildingId);
        return new NormalizedSensitiveCommand(buildingId, "USER_BUILDING_SCOPE", userId + ":" + buildingId,
                support.canonical(normalized, "建筑授权撤销命令无效"),
                "userId=" + userId + ";buildingId=" + buildingId);
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "建筑授权撤销命令无效");
        service.revokeBuilding(value.userId(), value.buildingId());
        return SensitiveOperationResult.none();
    }

    private record Command(Long userId, String buildingId) {
    }
}
