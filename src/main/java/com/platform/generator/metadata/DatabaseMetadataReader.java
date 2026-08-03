package com.platform.generator.metadata;

import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredTable;

import java.util.List;

/**
 * 数据库结构发现的抽象入口。
 *
 * <p>生成器服务通过本接口取得原始表、主键和字段信息，不接触 JDBC 细节；当前实现
 * {@link MysqlDatabaseMetadataReader} 从业务 {@code DataSource} 读取标准 JDBC 元数据。
 * 该边界只负责结构发现，不保存生成配置，也不渲染模板。</p>
 */
public interface DatabaseMetadataReader {
    /** 返回当前业务库中可供导入的表，不包含系统库和生成器自身配置表。 */
    List<DiscoveredTable> listTables();

    /** 完整读取指定表的主键和字段结构，作为首次导入配置的依据。 */
    DiscoveredTable readTable(String tableName);
}
