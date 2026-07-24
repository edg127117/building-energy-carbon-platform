package com.platform.generator.model.dto;

import java.util.List;

/**
 * 代码生成器 REST/服务边界使用的数据对象集合。
 *
 * <p>使用不可变 record，避免控制器直接暴露数据库实体，并把“导入请求、配置更新、
 * 配置详情”三个不同用途明确分开。</p>
 */
public final class GeneratorDtos {
    private GeneratorDtos() {}

    /** 数据库表选择列表中的一行；imported 表示该表是否已有生成配置。 */
    public record TableSummary(String tableName, String tableComment, boolean imported) {}

    /** 首次导入业务表时需要提供的最小命名配置，字段配置由数据库元数据自动创建。 */
    public record ImportTableRequest(
            String tableName,
            String moduleName,
            String businessName,
            String className,
            String packageName
    ) {}

    /** 返回给配置页面或 API 调用方的完整字段配置。 */
    public record ColumnConfig(
            Long id,
            String columnName,
            String columnComment,
            String jdbcType,
            String javaType,
            String javaField,
            boolean primaryKey,
            boolean nullable,
            boolean logicDelete,
            boolean list,
            boolean query,
            String queryType,
            boolean edit,
            boolean required,
            String componentType,
            int sortOrder
    ) {}

    /** 更新配置时允许调用方修改的字段属性；数据库原始名称和主键信息不可直接篡改。 */
    public record ColumnUpdate(
            Long id,
            String javaType,
            String javaField,
            boolean list,
            boolean query,
            String queryType,
            boolean edit,
            boolean required,
            String componentType,
            int sortOrder
    ) {}

    /** 整表配置更新请求，要求完整提交字段列表以便服务层进行一致性校验。 */
    public record UpdateGeneratorConfigRequest(
            String moduleName,
            String businessName,
            String className,
            String packageName,
            String idType,
            String logicDeleteColumn,
            String scopeType,
            String scopeColumn,
            List<String> readRoles,
            List<String> writeRoles,
            List<ColumnUpdate> columns
    ) {}

    /** 一张已导入表的完整生成配置视图。 */
    public record GeneratorConfigView(
            Long id,
            String tableName,
            String tableComment,
            String moduleName,
            String businessName,
            String className,
            String packageName,
            String idType,
            String logicDeleteColumn,
            String scopeType,
            String scopeColumn,
            List<String> readRoles,
            List<String> writeRoles,
            List<ColumnConfig> columns
    ) {}
}
