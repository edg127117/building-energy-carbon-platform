<#-- V1 Service 模板：定义分页查询和基础写操作的稳定业务边界。 -->
package ${basePackage}.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import ${basePackage}.model.entity.${table.className};
<#if buildingScope>
import java.util.Set;
</#if>

/** ${table.className} 的业务服务接口。 */
public interface ${table.className}Service extends IService<${table.className}> {
    /** 分页查询记录<#if buildingScope>，并限制在调用方可访问的建筑集合中</#if>。 */
    Result<IPage<${table.className}>> list(int page, int size, String keyword<#if buildingScope>, Set<String> accessibleBuildingIds</#if>);
    /** 新增一条记录。 */
    Result<${table.className}> add(${table.className} entity);
    /** 按主键更新一条记录。 */
    Result<${table.className}> update(${table.className} entity);
    /** 按主键删除记录；配置逻辑删除字段时由 MyBatis-Plus 执行逻辑删除。 */
    Result<Void> delete(${primaryKey.javaType} id);
}
