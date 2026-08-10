package com.platform.system.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 平台管理员维护菜单树时使用的受限请求模型。 */
public final class MenuAdminDtos {
    private MenuAdminDtos() {}

    /** 新增菜单只接受可维护业务字段，不允许客户端注入主键、时间或子树。 */
    public record CreateRequest(
            Long parentId,
            @NotBlank @Size(max = 50) String menuName,
            @NotBlank @Pattern(regexp = "[MCF]") String menuType,
            @Size(max = 200) String path,
            @Size(max = 255) String component,
            @Size(max = 100) String perms,
            @Size(max = 100) String icon,
            @Min(0) @Max(1) Integer visible,
            @Min(0) @Max(1) Integer status,
            Integer sortOrder) {}

    /** 更新菜单必须指定现有主键，其余字段与新增请求保持同一约束。 */
    public record UpdateRequest(
            @NotNull Long id,
            Long parentId,
            @NotBlank @Size(max = 50) String menuName,
            @NotBlank @Pattern(regexp = "[MCF]") String menuType,
            @Size(max = 200) String path,
            @Size(max = 255) String component,
            @Size(max = 100) String perms,
            @Size(max = 100) String icon,
            @Min(0) @Max(1) Integer visible,
            @Min(0) @Max(1) Integer status,
            Integer sortOrder) {}
}
