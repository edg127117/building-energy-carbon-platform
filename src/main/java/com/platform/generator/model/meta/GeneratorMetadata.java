package com.platform.generator.model.meta;

import java.util.List;
import java.util.Set;

/**
 * 与数据库实体、HTTP DTO 和具体模板引擎解耦的中立生成模型。
 *
 * <p>{@code GeneratorService} 先把 {@code gen_table/gen_column} 转换为这里的不可变模型，
 * 再交给 {@code GenerationTarget}。这样模板层只消费已经校验的业务描述，不依赖
 * MyBatis 实体、HTTP 请求对象或数据库连接。</p>
 */
public final class GeneratorMetadata {
    private GeneratorMetadata() {}

    /** 模板生成单个 Java 字段所需的标准化信息。 */
    public record ColumnMeta(
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

    /** 读取与写入操作允许使用的正式角色集合。 */
    public record PermissionMeta(List<String> readRoles, List<String> writeRoles) {
        public PermissionMeta {
            // 防御性复制，确保生成过程中配置不会被外部集合修改。
            readRoles = List.copyOf(readRoles);
            writeRoles = List.copyOf(writeRoles);
        }
    }

    /** 数据范围配置；BUILDING 时 columnName/javaField 指向建筑字段。 */
    public record DataScopeMeta(String type, String columnName, String javaField) {}

    /** 一张表经过校验后的完整、目标无关的生成描述。 */
    public record TableMeta(
            Long id,
            String tableName,
            String tableComment,
            String moduleName,
            String businessName,
            String className,
            String packageName,
            String idType,
            String logicDeleteColumn,
            PermissionMeta permissions,
            DataScopeMeta dataScope,
            List<ColumnMeta> columns
    ) {
        public TableMeta {
            columns = List.copyOf(columns);
        }
    }

    /**
     * 一次输出任务的根上下文，包含表配置、唯一主键和需要写入源码的 import。
     */
    public record GenerationContext(TableMeta table, ColumnMeta primaryKey, Set<String> imports) {
        public GenerationContext {
            imports = Set.copyOf(imports);
        }
    }

    /** JDBC 元数据阶段发现的原始字段，还没有应用 Java 生成配置。 */
    public record DiscoveredColumn(
            String columnName,
            String columnComment,
            String jdbcType,
            boolean nullable,
            boolean autoIncrement,
            int sortOrder
    ) {}

    /** JDBC 元数据阶段发现的原始表结构。 */
    public record DiscoveredTable(
            String tableName,
            String tableComment,
            List<String> primaryKeys,
            List<DiscoveredColumn> columns
    ) {
        public DiscoveredTable {
            primaryKeys = List.copyOf(primaryKeys);
            columns = List.copyOf(columns);
        }
    }
}
