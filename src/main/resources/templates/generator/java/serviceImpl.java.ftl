<#-- V1 ServiceImpl 模板：生成可编译的单表查询、建筑范围过滤和 CRUD 实现。 -->
package ${basePackage}.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import ${basePackage}.mapper.${table.className}Mapper;
import ${basePackage}.model.entity.${table.className};
import ${basePackage}.service.${table.className}Service;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
<#if buildingScope>
import java.util.Set;
</#if>

/**
 * ${table.className} 的基础业务实现。
 * 生成代码负责通用 CRUD 与数据范围约束，领域规则应在合并后继续补充。
 */
@Service
public class ${table.className}ServiceImpl
        extends ServiceImpl<${table.className}Mapper, ${table.className}>
        implements ${table.className}Service {

    /** 执行分页和通用关键字查询<#if buildingScope>，普通用户无建筑授权时直接返回空页</#if>。 */
    @Override
    public Result<IPage<${table.className}>> list(int page, int size, String keyword<#if buildingScope>, Set<String> accessibleBuildingIds</#if>) {
        Page<${table.className}> pageParam = new Page<>(page, size);
<#if buildingScope>
        if (accessibleBuildingIds != null && accessibleBuildingIds.isEmpty()) return Result.success(pageParam);
</#if>
        // 查询条件全部使用 Lambda 字段引用，避免手写数据库列名。
        LambdaQueryWrapper<${table.className}> wrapper = new LambdaQueryWrapper<>();
<#if buildingScope>
        if (accessibleBuildingIds != null) wrapper.in(${table.className}::get${table.dataScope.javaField?cap_first}, accessibleBuildingIds);
</#if>
<#if queryColumns?size gt 0>
        if (StringUtils.hasText(keyword)) {
            wrapper.and(query -> query
<#list queryColumns as column>
                    <#if column?index == 0>.like<#else>.or().like</#if>(${table.className}::get${column.javaField?cap_first}, keyword)
</#list>
            );
        }
</#if>
        return Result.success(this.page(pageParam, wrapper));
    }

    /** 保存实体并返回写入后的对象。 */
    @Override
    public Result<${table.className}> add(${table.className} entity) {
        this.save(entity);
        return Result.success(entity);
    }

    /** 根据实体主键更新记录。 */
    @Override
    public Result<${table.className}> update(${table.className} entity) {
        this.updateById(entity);
        return Result.success(entity);
    }

    /** 根据配置的主键类型删除记录。 */
    @Override
    public Result<Void> delete(${primaryKey.javaType} id) {
        this.removeById(id);
        return Result.success();
    }
}
