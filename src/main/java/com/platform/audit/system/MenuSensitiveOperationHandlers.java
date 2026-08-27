package com.platform.audit.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.system.model.dto.MenuAdminDtos;
import com.platform.system.service.SysMenuService;
import com.platform.system.service.SysRoleAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
/** 执行已批准的角色菜单全量替换，菜单只影响导航而不授予后端 API 权限。 */
class ReplaceRoleMenusHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "REPLACE_ROLE_MENUS";
    private final SystemSensitiveCommandSupport support;
    private final SysRoleAdminService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "角色菜单变更命令无效");
        long roleId = SystemSensitiveCommandSupport.requireId(value.roleId(), "角色菜单变更命令无效");
        List<Long> menuIds = SystemSensitiveCommandSupport.ids(value.menuIds(), 1000, "角色菜单变更命令无效");
        Command normalized = new Command(roleId, menuIds);
        return new NormalizedSensitiveCommand(null, "ROLE_MENU_ASSIGNMENT", Long.toString(roleId),
                support.canonical(normalized, "角色菜单变更命令无效"),
                "roleId=" + roleId + ";menuCount=" + menuIds.size());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "角色菜单变更命令无效");
        service.replaceMenus(value.roleId(), value.menuIds());
        return SensitiveOperationResult.none();
    }

    private record Command(Long roleId, List<Long> menuIds) {
    }
}

/** 执行已批准的菜单新增命令，申请目标使用稳定申请内逻辑标识。 */
@Component
@RequiredArgsConstructor
class CreateMenuHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "CREATE_MENU";
    private final SystemSensitiveCommandSupport support;
    private final SysMenuService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        CreateCommand value = support.read(command, CreateCommand.class, "菜单新增命令无效");
        CreateCommand normalized = normalize(value);
        return new NormalizedSensitiveCommand(null, "MENU_DEFINITION", "NEW:" + normalized.menuName(),
                support.canonical(normalized, "菜单新增命令无效"),
                "menuName=" + normalized.menuName() + ";menuType=" + normalized.menuType());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        CreateCommand value = support.readCanonical(command.canonicalJson(), CreateCommand.class, "菜单新增命令无效");
        service.add(value.toRequest());
        return SensitiveOperationResult.none();
    }

    private static CreateCommand normalize(CreateCommand value) {
        String type = SystemSensitiveCommandSupport.requireText(value.menuType(), 1, "菜单新增命令无效").toUpperCase();
        if (!Set.of("M", "C", "F").contains(type)) throw SystemSensitiveCommandSupport.invalid("菜单新增命令无效");
        return new CreateCommand(value.parentId() == null ? 0L : value.parentId(),
                SystemSensitiveCommandSupport.requireText(value.menuName(), 50, "菜单新增命令无效"), type,
                SystemSensitiveCommandSupport.optionalText(value.path(), 200, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.component(), 255, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.perms(), 100, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.icon(), 100, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.flag(value.visible(), 1, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.flag(value.status(), 1, "菜单新增命令无效"),
                SystemSensitiveCommandSupport.integer(value.sortOrder(), 0));
    }

    private record CreateCommand(Long parentId, String menuName, String menuType, String path,
                                 String component, String perms, String icon, Integer visible,
                                 Integer status, Integer sortOrder) {
        MenuAdminDtos.CreateRequest toRequest() {
            return new MenuAdminDtos.CreateRequest(parentId, menuName, menuType, path, component,
                    perms, icon, visible, status, sortOrder);
        }
    }
}

/** 执行已批准的菜单更新命令，父链和循环约束由菜单领域服务在事务内复核。 */
@Component
@RequiredArgsConstructor
class UpdateMenuHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "UPDATE_MENU";
    private final SystemSensitiveCommandSupport support;
    private final SysMenuService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        UpdateCommand value = support.read(command, UpdateCommand.class, "菜单更新命令无效");
        long id = SystemSensitiveCommandSupport.requireId(value.id(), "菜单更新命令无效");
        String type = SystemSensitiveCommandSupport.requireText(value.menuType(), 1, "菜单更新命令无效").toUpperCase();
        if (!Set.of("M", "C", "F").contains(type)) throw SystemSensitiveCommandSupport.invalid("菜单更新命令无效");
        UpdateCommand normalized = new UpdateCommand(id, value.parentId() == null ? 0L : value.parentId(),
                SystemSensitiveCommandSupport.requireText(value.menuName(), 50, "菜单更新命令无效"), type,
                SystemSensitiveCommandSupport.optionalText(value.path(), 200, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.component(), 255, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.perms(), 100, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.optionalText(value.icon(), 100, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.flag(value.visible(), 1, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.flag(value.status(), 1, "菜单更新命令无效"),
                SystemSensitiveCommandSupport.integer(value.sortOrder(), 0));
        return new NormalizedSensitiveCommand(null, "MENU_DEFINITION", Long.toString(id),
                support.canonical(normalized, "菜单更新命令无效"),
                "menuId=" + id + ";menuName=" + normalized.menuName());
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        UpdateCommand value = support.readCanonical(command.canonicalJson(), UpdateCommand.class, "菜单更新命令无效");
        service.update(new MenuAdminDtos.UpdateRequest(value.id(), value.parentId(), value.menuName(), value.menuType(),
                value.path(), value.component(), value.perms(), value.icon(), value.visible(), value.status(), value.sortOrder()));
        return SensitiveOperationResult.none();
    }

    private record UpdateCommand(Long id, Long parentId, String menuName, String menuType, String path,
                                 String component, String perms, String icon, Integer visible,
                                 Integer status, Integer sortOrder) {
    }
}

/** 执行已批准的菜单删除命令，领域服务继续保护子菜单和角色关联一致性。 */
@Component
@RequiredArgsConstructor
class DeleteMenuHandler implements SensitiveOperationHandler {
    static final String OPERATION_CODE = "DELETE_MENU";
    private final SystemSensitiveCommandSupport support;
    private final SysMenuService service;

    @Override public String operationCode() { return OPERATION_CODE; }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        Command value = support.read(command, Command.class, "菜单删除命令无效");
        long id = SystemSensitiveCommandSupport.requireId(value.menuId(), "菜单删除命令无效");
        Command normalized = new Command(id);
        return new NormalizedSensitiveCommand(null, "MENU_DEFINITION", Long.toString(id),
                support.canonical(normalized, "菜单删除命令无效"), "menuId=" + id);
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        Command value = support.readCanonical(command.canonicalJson(), Command.class, "菜单删除命令无效");
        service.delete(value.menuId());
        return SensitiveOperationResult.none();
    }

    private record Command(Long menuId) {
    }
}
