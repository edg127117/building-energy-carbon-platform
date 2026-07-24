<#-- V1 生成包说明模板。 -->
# ${table.className} 后端 CRUD

- 数据表：`${table.tableName}`
- 业务模块：`${table.moduleName}`
- 基础包：`${basePackage}`
- 主键：`${primaryKey.columnName}` / `${table.idType}`
- 数据范围：`${table.dataScope.type}`
- 读取角色：`${table.permissions.readRoles?join(", ")}`
- 写入角色：`${table.permissions.writeRoles?join(", ")}`

将 `src/main/java` 下的生成文件合并到项目后，执行 `mvn test` 验证。生成器不会自动覆盖工作区源码。

> 生成代码提供通用单表能力，正式使用前仍需结合业务补充参数校验、领域规则和接口测试。
