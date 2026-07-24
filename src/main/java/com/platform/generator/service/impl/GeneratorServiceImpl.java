package com.platform.generator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.generator.mapper.GenColumnMapper;
import com.platform.generator.mapper.GenTableMapper;
import com.platform.generator.metadata.DatabaseMetadataReader;
import com.platform.generator.metadata.GeneratorNames;
import com.platform.generator.metadata.JavaTypeMapper;
import com.platform.generator.model.dto.GeneratorDtos.ColumnConfig;
import com.platform.generator.model.dto.GeneratorDtos.ColumnUpdate;
import com.platform.generator.model.dto.GeneratorDtos.GeneratorConfigView;
import com.platform.generator.model.dto.GeneratorDtos.ImportTableRequest;
import com.platform.generator.model.dto.GeneratorDtos.TableSummary;
import com.platform.generator.model.dto.GeneratorDtos.UpdateGeneratorConfigRequest;
import com.platform.generator.model.entity.GenColumn;
import com.platform.generator.model.entity.GenTable;
import com.platform.generator.model.meta.GeneratorMetadata.ColumnMeta;
import com.platform.generator.model.meta.GeneratorMetadata.DataScopeMeta;
import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredColumn;
import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredTable;
import com.platform.generator.model.meta.GeneratorMetadata.GenerationContext;
import com.platform.generator.model.meta.GeneratorMetadata.PermissionMeta;
import com.platform.generator.model.meta.GeneratorMetadata.TableMeta;
import com.platform.generator.service.GeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 代码生成器配置与元数据编排服务。
 *
 * <p>它把数据库发现结果转换成可编辑配置，并在生成前执行白名单、主键、字段、角色和
 * 建筑数据范围校验。该服务只维护 {@code gen_table/gen_column}，从不更新真实业务表。</p>
 */
@Service
@RequiredArgsConstructor
public class GeneratorServiceImpl implements GeneratorService {
    /** V1 明确支持的有限选项，使用白名单避免任意文本进入模板和安全表达式。 */
    private static final Set<String> ID_TYPES = Set.of("AUTO", "ASSIGN_ID", "INPUT");
    private static final Set<String> SCOPE_TYPES = Set.of("NONE", "BUILDING");
    private static final Set<String> ROLES = Set.of(
            "BUILDING_OWNER", "ENERGY_MANAGER", "THIRD_PARTY", "PLATFORM_ADMIN");
    private static final Set<String> QUERY_TYPES = Set.of("EQ", "LIKE", "BETWEEN", "GT", "GE", "LT", "LE");
    private static final Set<String> COMPONENT_TYPES = Set.of(
            "TEXT", "TEXTAREA", "NUMBER", "SELECT", "DATE", "DATETIME", "SWITCH");
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:\\[\\])?$");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final GenTableMapper tableMapper;
    private final GenColumnMapper columnMapper;
    private final DatabaseMetadataReader metadataReader;
    private final JavaTypeMapper javaTypeMapper;
    private final ObjectMapper objectMapper;

    /** 合并数据库发现结果与已导入配置状态，供表选择列表展示。 */
    @Override
    public List<TableSummary> listTables() {
        Set<String> imported = tableMapper.selectList(null).stream()
                .map(GenTable::getTableName).collect(Collectors.toSet());
        return metadataReader.listTables().stream()
                .map(table -> new TableSummary(table.tableName(), table.tableComment(),
                        imported.contains(table.tableName())))
                .toList();
    }

    /**
     * 首次导入业务表：读取实时数据库元数据，创建表配置，并按字段顺序创建默认字段配置。
     * 已导入表、无主键表和联合主键表会被明确拒绝。
     */
    @Override
    @Transactional
    public GeneratorConfigView importTable(ImportTableRequest request) {
        if (request == null) throw new BusinessException(400, "导入配置不能为空");
        String tableName = GeneratorNames.requireDatabaseName(request.tableName(), "表名");
        if (tableMapper.selectCount(new LambdaQueryWrapper<GenTable>()
                .eq(GenTable::getTableName, tableName)) > 0) {
            throw new BusinessException(409, "业务表已经导入: " + tableName);
        }
        String moduleName = GeneratorNames.requireJavaIdentifier(request.moduleName(), "模块名");
        String businessName = GeneratorNames.requireJavaIdentifier(request.businessName(), "业务名");
        String className = GeneratorNames.requireJavaIdentifier(request.className(), "类名");
        String packageName = GeneratorNames.requirePackageName(request.packageName());

        // 导入时以数据库实时结构为准，调用方不能伪造主键、字段类型和自增属性。
        DiscoveredTable discovered = metadataReader.readTable(tableName);
        requireSinglePrimaryKey(discovered);
        String primaryKey = discovered.primaryKeys().getFirst();
        DiscoveredColumn primaryColumn = discovered.columns().stream()
                .filter(column -> column.columnName().equalsIgnoreCase(primaryKey)).findFirst()
                .orElseThrow(() -> new BusinessException(400, "主键字段元数据缺失: " + primaryKey));

        // 表级默认值偏向安全：仅平台管理员可读写，数据范围默认不放宽到任意字段。
        GenTable table = new GenTable();
        table.setTableName(discovered.tableName());
        table.setTableComment(discovered.tableComment());
        table.setModuleName(moduleName);
        table.setBusinessName(businessName);
        table.setClassName(className);
        table.setPackageName(packageName);
        table.setIdType(primaryColumn.autoIncrement() ? "AUTO" : "INPUT");
        table.setLogicDeleteColumn(discovered.columns().stream()
                .map(DiscoveredColumn::columnName).filter("del_flag"::equalsIgnoreCase)
                .findFirst().orElse(null));
        table.setScopeType("NONE");
        table.setScopeColumn(null);
        table.setReadRoles(writeJson(List.of("PLATFORM_ADMIN")));
        table.setWriteRoles(writeJson(List.of("PLATFORM_ADMIN")));
        table.setGenerateMode("JAVA_ZIP");
        table.setStatus(1);
        tableMapper.insert(table);

        // 字段级默认配置由数据库约束推导，后续可通过更新接口进行完整调整。
        for (DiscoveredColumn source : discovered.columns()) {
            GenColumn column = new GenColumn();
            column.setTableId(table.getId());
            column.setColumnName(source.columnName());
            column.setColumnComment(source.columnComment());
            column.setJdbcType(source.jdbcType());
            column.setJavaType(javaTypeMapper.map(source.jdbcType()));
            column.setJavaField(GeneratorNames.toCamelCase(source.columnName()));
            column.setIsPrimaryKey(bool(source.columnName().equalsIgnoreCase(primaryKey)));
            column.setIsNullable(bool(source.nullable()));
            column.setIsLogicDelete(bool(source.columnName().equalsIgnoreCase(table.getLogicDeleteColumn())));
            column.setIsList(1);
            column.setIsQuery(0);
            column.setQueryType("EQ");
            column.setIsEdit(bool(!source.autoIncrement()));
            column.setIsRequired(bool(!source.nullable() && !source.autoIncrement()));
            column.setComponentType(defaultComponent(column.getJavaType()));
            column.setSortOrder(source.sortOrder());
            columnMapper.insert(column);
        }
        return detail(table.getId());
    }

    /** 返回配置实体与有序字段的只读 DTO 视图。 */
    @Override
    public GeneratorConfigView detail(Long id) {
        GenTable table = requireTable(id);
        return toView(table, columns(id));
    }

    /**
     * 完整更新生成配置。请求必须包含全部字段，防止部分提交意外丢失主键或数据范围字段。
     */
    @Override
    @Transactional
    public GeneratorConfigView update(Long id, UpdateGeneratorConfigRequest request) {
        GenTable table = requireTable(id);
        if (request == null) throw new BusinessException(400, "更新配置不能为空");
        table.setModuleName(GeneratorNames.requireJavaIdentifier(request.moduleName(), "模块名"));
        table.setBusinessName(GeneratorNames.requireJavaIdentifier(request.businessName(), "业务名"));
        table.setClassName(GeneratorNames.requireJavaIdentifier(request.className(), "类名"));
        table.setPackageName(GeneratorNames.requirePackageName(request.packageName()));
        table.setIdType(requireAllowed(request.idType(), ID_TYPES, "主键策略"));
        table.setScopeType(requireAllowed(request.scopeType(), SCOPE_TYPES, "数据范围类型"));
        table.setLogicDeleteColumn(blankToNull(request.logicDeleteColumn()));
        table.setScopeColumn(blankToNull(request.scopeColumn()));
        table.setReadRoles(writeJson(validateRoles(request.readRoles(), "读取角色")));
        table.setWriteRoles(writeJson(validateRoles(request.writeRoles(), "写入角色")));

        // 只允许修改当前表已有的字段 ID，数据库原始字段名和主键归属保持冻结。
        List<GenColumn> existing = columns(id);
        Map<Long, GenColumn> byId = existing.stream().collect(Collectors.toMap(GenColumn::getId, Function.identity()));
        if (request.columns() == null || request.columns().size() != existing.size()) {
            throw new BusinessException(400, "字段配置必须完整提交");
        }
        Set<Long> seen = new HashSet<>();
        for (ColumnUpdate update : request.columns()) {
            GenColumn column = byId.get(update.id());
            if (column == null || !seen.add(update.id())) throw new BusinessException(400, "字段配置 ID 不合法");
            column.setJavaType(requireJavaType(update.javaType()));
            column.setJavaField(GeneratorNames.requireJavaIdentifier(update.javaField(), "Java 字段名"));
            column.setIsList(bool(update.list()));
            column.setIsQuery(bool(update.query()));
            column.setQueryType(requireAllowed(update.queryType(), QUERY_TYPES, "查询方式"));
            column.setIsEdit(bool(update.edit()));
            column.setIsRequired(bool(update.required()));
            column.setComponentType(requireAllowed(update.componentType(), COMPONENT_TYPES, "控件类型"));
            column.setSortOrder(update.sortOrder());
            column.setIsLogicDelete(bool(column.getColumnName().equals(table.getLogicDeleteColumn())));
            columnMapper.updateById(column);
        }
        validateTableSettings(table, existing);
        tableMapper.updateById(table);
        return detail(id);
    }

    /** 删除生成配置及其字段配置，不执行任何业务表 DDL。 */
    @Override
    @Transactional
    public void delete(Long id) {
        requireTable(id);
        columnMapper.delete(new LambdaQueryWrapper<GenColumn>().eq(GenColumn::getTableId, id));
        tableMapper.deleteById(id);
    }

    /**
     * 构建目标无关的不可变生成上下文。这是输出前的最后一道完整校验边界。
     */
    @Override
    public GenerationContext buildContext(Long id) {
        GenTable table = requireTable(id);
        List<GenColumn> columns = columns(id);
        validateTableSettings(table, columns);
        List<GenColumn> primaryKeys = columns.stream().filter(column -> yes(column.getIsPrimaryKey())).toList();
        if (primaryKeys.size() != 1) throw new BusinessException(400, "仅支持单列主键，配置 ID: " + id);

        // 从持久化实体转换为模板友好的简单类型，并保持用户配置的字段顺序。
        List<ColumnMeta> metas = columns.stream().sorted(Comparator.comparingInt(GenColumn::getSortOrder))
                .map(this::toMeta).toList();
        ColumnMeta primary = metas.stream().filter(ColumnMeta::primaryKey).findFirst().orElseThrow();
        ColumnMeta scope = table.getScopeColumn() == null ? null : metas.stream()
                .filter(column -> column.columnName().equals(table.getScopeColumn())).findFirst().orElse(null);
        DataScopeMeta dataScope = new DataScopeMeta(table.getScopeType(), table.getScopeColumn(),
                scope == null ? null : scope.javaField());
        PermissionMeta permissions = new PermissionMeta(readJson(table.getReadRoles()), readJson(table.getWriteRoles()));
        TableMeta tableMeta = new TableMeta(table.getId(), table.getTableName(), table.getTableComment(),
                table.getModuleName(), table.getBusinessName(), table.getClassName(), table.getPackageName(),
                table.getIdType(), table.getLogicDeleteColumn(), permissions, dataScope, metas);
        // java.lang 类型无需 import，其余完整类型去重后交给输出目标排序。
        Set<String> imports = columns.stream().map(GenColumn::getJavaType)
                .filter(type -> type.contains(".") && !type.startsWith("java.lang."))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new GenerationContext(tableMeta, primary, imports);
    }

    /** 校验表级配置引用的字段真实存在，并验证 BUILDING 范围字段类型。 */
    private void validateTableSettings(GenTable table, List<GenColumn> columns) {
        GeneratorNames.requireJavaIdentifier(table.getModuleName(), "模块名");
        GeneratorNames.requireJavaIdentifier(table.getBusinessName(), "业务名");
        GeneratorNames.requireJavaIdentifier(table.getClassName(), "类名");
        GeneratorNames.requirePackageName(table.getPackageName());
        requireAllowed(table.getIdType(), ID_TYPES, "主键策略");
        requireAllowed(table.getScopeType(), SCOPE_TYPES, "数据范围类型");
        validateRoles(readJson(table.getReadRoles()), "读取角色");
        validateRoles(readJson(table.getWriteRoles()), "写入角色");
        Map<String, GenColumn> byName = columns.stream()
                .collect(Collectors.toMap(GenColumn::getColumnName, Function.identity()));
        if (table.getLogicDeleteColumn() != null && !byName.containsKey(table.getLogicDeleteColumn())) {
            throw new BusinessException(400, "逻辑删除字段不存在: " + table.getLogicDeleteColumn());
        }
        if ("BUILDING".equals(table.getScopeType())) {
            GenColumn scope = byName.get(table.getScopeColumn());
            if (scope == null) throw new BusinessException(400, "建筑数据范围字段不存在");
            if (!"java.lang.String".equals(scope.getJavaType())) {
                throw new BusinessException(400, "建筑数据范围字段必须是 String 类型");
            }
        } else if (table.getScopeColumn() != null) {
            throw new BusinessException(400, "NONE 数据范围不能配置范围字段");
        }
    }

    /** 把数据库实体转换为不携带持久化行为的中立字段模型。 */
    private ColumnMeta toMeta(GenColumn column) {
        return new ColumnMeta(column.getId(), column.getColumnName(), column.getColumnComment(),
                column.getJdbcType(), javaTypeMapper.simpleName(column.getJavaType()), column.getJavaField(),
                yes(column.getIsPrimaryKey()), yes(column.getIsNullable()), yes(column.getIsLogicDelete()),
                yes(column.getIsList()), yes(column.getIsQuery()), column.getQueryType(), yes(column.getIsEdit()),
                yes(column.getIsRequired()), column.getComponentType(), column.getSortOrder());
    }

    /** 组装 REST 层使用的完整配置视图。 */
    private GeneratorConfigView toView(GenTable table, List<GenColumn> columns) {
        List<ColumnConfig> configs = columns.stream().sorted(Comparator.comparingInt(GenColumn::getSortOrder))
                .map(column -> new ColumnConfig(column.getId(), column.getColumnName(), column.getColumnComment(),
                        column.getJdbcType(), column.getJavaType(), column.getJavaField(), yes(column.getIsPrimaryKey()),
                        yes(column.getIsNullable()), yes(column.getIsLogicDelete()), yes(column.getIsList()),
                        yes(column.getIsQuery()), column.getQueryType(), yes(column.getIsEdit()),
                        yes(column.getIsRequired()), column.getComponentType(), column.getSortOrder()))
                .toList();
        return new GeneratorConfigView(table.getId(), table.getTableName(), table.getTableComment(),
                table.getModuleName(), table.getBusinessName(), table.getClassName(), table.getPackageName(),
                table.getIdType(), table.getLogicDeleteColumn(), table.getScopeType(), table.getScopeColumn(),
                readJson(table.getReadRoles()), readJson(table.getWriteRoles()), configs);
    }

    /** 查询配置，不存在时统一返回可映射为 HTTP 状态码的业务异常。 */
    private GenTable requireTable(Long id) {
        if (id == null) throw new BusinessException(400, "生成配置 ID 不能为空");
        GenTable table = tableMapper.selectById(id);
        if (table == null) throw new BusinessException(404, "生成配置不存在: " + id);
        return table;
    }

    /** 按显式顺序读取指定表的全部字段配置。 */
    private List<GenColumn> columns(Long tableId) {
        return columnMapper.selectList(new LambdaQueryWrapper<GenColumn>()
                .eq(GenColumn::getTableId, tableId).orderByAsc(GenColumn::getSortOrder));
    }

    /** V1 生成模板只支持单列主键，避免输出错误的 MyBatis-Plus 主键代码。 */
    private void requireSinglePrimaryKey(DiscoveredTable table) {
        if (table.primaryKeys().isEmpty()) throw new BusinessException(400, "业务表没有主键: " + table.tableName());
        if (table.primaryKeys().size() > 1) throw new BusinessException(400, "暂不支持联合主键: " + table.tableName());
    }

    /** 将角色规范化为大写，并限制为冻结书定义的四类正式角色。 */
    private List<String> validateRoles(List<String> roles, String label) {
        if (roles == null || roles.isEmpty()) throw new BusinessException(400, label + "不能为空");
        List<String> normalized = roles.stream().map(role -> role == null ? "" : role.toUpperCase(Locale.ROOT))
                .distinct().toList();
        if (!ROLES.containsAll(normalized)) throw new BusinessException(400, label + "包含未知角色");
        return normalized;
    }

    /** 通用枚举白名单校验，返回规范化后的大写值。 */
    private String requireAllowed(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(400, label + "不合法: " + value);
        return normalized;
    }

    /** 校验调用方提供的完整 Java 类型名，阻止表达式或模板片段注入。 */
    private String requireJavaType(String value) {
        if (value == null || !JAVA_TYPE.matcher(value).matches()) {
            throw new BusinessException(400, "Java 类型不合法");
        }
        return value;
    }

    /** 使用 JSON 保存角色集合，避免自定义分隔符产生歧义。 */
    private String writeJson(List<String> roles) {
        try { return objectMapper.writeValueAsString(roles); }
        catch (JsonProcessingException e) { throw new BusinessException(500, "角色配置序列化失败"); }
    }

    /** 从持久化 JSON 恢复角色集合，损坏数据不会静默降级。 */
    private List<String> readJson(String value) {
        try { return objectMapper.readValue(value, STRING_LIST); }
        catch (JsonProcessingException e) { throw new BusinessException(500, "角色配置格式损坏"); }
    }

    /** 根据 Java 类型给后续可视化配置提供一个保守的默认组件类型。 */
    private String defaultComponent(String javaType) {
        if ("java.lang.Boolean".equals(javaType)) return "SWITCH";
        if ("java.util.Date".equals(javaType)) return "DATETIME";
        if (Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
                "java.math.BigDecimal").contains(javaType)) return "NUMBER";
        return "TEXT";
    }

    private static int bool(boolean value) { return value ? 1 : 0; }
    private static boolean yes(Integer value) { return value != null && value == 1; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
