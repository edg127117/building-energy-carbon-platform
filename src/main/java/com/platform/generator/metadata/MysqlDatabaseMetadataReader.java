package com.platform.generator.metadata;

import com.platform.framework.exception.BusinessException;
import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredColumn;
import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于 JDBC {@link DatabaseMetaData} 的业务表结构读取器。
 *
 * <p>虽然名称标明 MySQL，但实现只使用标准 JDBC 元数据 API，因此测试环境可使用 H2。
 * 读取过程严格排除系统 Schema 以及 {@code gen_table/gen_column}，防止生成器生成自身。</p>
 */
@Component
@RequiredArgsConstructor
public class MysqlDatabaseMetadataReader implements DatabaseMetadataReader {
    private static final Set<String> EXCLUDED_SCHEMAS = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
    private static final Set<String> INTERNAL_TABLES = Set.of("gen_table", "gen_column");

    private final DataSource dataSource;

    /** 读取可导入的普通业务表，仅返回列表展示需要的概要信息。 */
    @Override
    public List<DiscoveredTable> listTables() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<DiscoveredTable> tables = new ArrayList<>();
            try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    if (excluded(schema, name)) continue;
                    tables.add(new DiscoveredTable(name, blankToNull(rs.getString("REMARKS")), List.of(), List.of()));
                }
            }
            tables.sort(Comparator.comparing(DiscoveredTable::tableName));
            return tables;
        } catch (SQLException e) {
            throw new BusinessException(500, "读取数据库表结构失败");
        }
    }

    /** 读取单表的有序主键、字段、空值和自增信息，供生成配置初始化使用。 */
    @Override
    public DiscoveredTable readTable(String tableName) {
        GeneratorNames.requireDatabaseName(tableName, "表名");
        if (INTERNAL_TABLES.contains(tableName.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(400, "生成器内部表不允许导入");
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String actualName = null;
            String comment = null;
            try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String candidate = rs.getString("TABLE_NAME");
                    if (!excluded(schema, candidate) && candidate.equalsIgnoreCase(tableName)) {
                        actualName = candidate;
                        comment = blankToNull(rs.getString("REMARKS"));
                        break;
                    }
                }
            }
            if (actualName == null) throw new BusinessException(404, "业务表不存在: " + tableName);

            // 按 KEY_SEQ 保存主键，既能保持数据库声明顺序，也能让服务层识别联合主键。
            Map<Short, String> primaryKeysBySeq = new LinkedHashMap<>();
            try (ResultSet rs = metadata.getPrimaryKeys(connection.getCatalog(), null, actualName)) {
                while (rs.next()) primaryKeysBySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            List<String> primaryKeys = primaryKeysBySeq.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();

            // ORDINAL_POSITION 保证生成实体字段顺序与原表字段顺序一致。
            List<DiscoveredColumn> columns = new ArrayList<>();
            try (ResultSet rs = metadata.getColumns(connection.getCatalog(), null, actualName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String typeName = rs.getString("TYPE_NAME");
                    boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    boolean autoIncrement = "YES".equalsIgnoreCase(safeGet(rs, "IS_AUTOINCREMENT"));
                    columns.add(new DiscoveredColumn(columnName, blankToNull(rs.getString("REMARKS")),
                            typeName, nullable, autoIncrement, rs.getInt("ORDINAL_POSITION")));
                }
            }
            columns.sort(Comparator.comparingInt(DiscoveredColumn::sortOrder));
            return new DiscoveredTable(actualName, comment, primaryKeys, columns);
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException(500, "读取业务表结构失败: " + tableName);
        }
    }

    /** 统一过滤系统 Schema、空名称和生成器内部表。 */
    private boolean excluded(String schema, String tableName) {
        String normalizedSchema = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        String normalizedTable = tableName == null ? "" : tableName.toLowerCase(Locale.ROOT);
        return EXCLUDED_SCHEMAS.contains(normalizedSchema) || INTERNAL_TABLES.contains(normalizedTable);
    }

    /** 某些 JDBC 驱动不提供可选元数据列，缺失时按未知值处理。 */
    private static String safeGet(ResultSet rs, String column) {
        try { return rs.getString(column); } catch (SQLException ignored) { return null; }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
