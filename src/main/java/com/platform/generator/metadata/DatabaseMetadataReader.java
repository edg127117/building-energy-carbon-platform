package com.platform.generator.metadata;

import com.platform.generator.model.meta.GeneratorMetadata.DiscoveredTable;

import java.util.List;

/**
 * 数据库结构发现的抽象入口。
 *
 * <p>生成器服务只依赖本接口，不直接依赖某一种数据库。V1 提供 JDBC/MySQL 兼容实现，
 * 后续若支持其他数据库，可以新增实现而不改动生成流程。</p>
 */
public interface DatabaseMetadataReader {
    /** 返回当前业务库中可供导入的表，不包含系统库和生成器自身配置表。 */
    List<DiscoveredTable> listTables();

    /** 完整读取指定表的主键和字段结构，作为首次导入配置的依据。 */
    DiscoveredTable readTable(String tableName);
}
