<#-- V1 Controller 模板：复用项目的角色鉴权、JWT 上下文和建筑数据范围服务。 -->
package ${basePackage}.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
<#if buildingScope>
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
</#if>
import ${basePackage}.model.entity.${table.className};
import ${basePackage}.service.${table.className}Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
<#if buildingScope>
import org.springframework.security.core.Authentication;
</#if>
import org.springframework.web.bind.annotation.*;

/**
 * ${table.className} 的 REST 管理接口。
 * 读取和写入权限来自生成配置，BUILDING 范围会复用平台统一的数据权限服务。
 */
@RestController
@RequestMapping("/${table.businessName}")
@RequiredArgsConstructor
public class ${table.className}Controller {
    private final ${table.className}Service service;
<#if buildingScope>
    private final BuildingScopeService buildingScopeService;
</#if>

    /** 分页查询<#if buildingScope>当前用户有权访问的建筑数据</#if>。 */
    @GetMapping("/list")
    @PreAuthorize("${readExpression}")
    public Result<IPage<${table.className}>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword<#if buildingScope>, Authentication authentication</#if>) {
        return service.list(page, size, keyword<#if buildingScope>,
                buildingScopeService.getAccessibleBuildingIds(SecurityUser.userId(authentication), SecurityUser.roles(authentication))</#if>);
    }

    /** 查询单条记录<#if buildingScope>并校验其所属建筑权限</#if>。 */
    @GetMapping("/detail/{id}")
    @PreAuthorize("${readExpression}")
    public Result<${table.className}> detail(@PathVariable ${primaryKey.javaType} id<#if buildingScope>, Authentication authentication</#if>) {
        ${table.className} entity = service.getById(id);
        if (entity == null) throw new BusinessException(404, "记录不存在");
<#if buildingScope>
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                entity.get${table.dataScope.javaField?cap_first}());
</#if>
        return Result.success(entity);
    }

    /** 新增记录，仅允许配置的写入角色调用。 */
    @PostMapping("/add")
    @PreAuthorize("${writeExpression}")
    public Result<${table.className}> add(@Valid @RequestBody ${table.className} entity) {
        return service.add(entity);
    }

    /** 更新记录，仅允许配置的写入角色调用。 */
    @PutMapping("/update")
    @PreAuthorize("${writeExpression}")
    public Result<${table.className}> update(@Valid @RequestBody ${table.className} entity) {
        return service.update(entity);
    }

    /** 删除记录，仅允许配置的写入角色调用。 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("${writeExpression}")
    public Result<Void> delete(@PathVariable ${primaryKey.javaType} id) {
        return service.delete(id);
    }
}
