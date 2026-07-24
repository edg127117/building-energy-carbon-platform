# 后端代码生成器 V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建仅限 `PLATFORM_ADMIN` 使用的后端 Java ZIP 代码生成器，并以统一元数据内核为 V2 JSON Schema 和 V3 拖拽配置预留扩展边界。

**Architecture:** 从当前 MySQL 业务库读取表与字段结构，导入 `gen_table`、`gen_column` 后形成中立的 `GenerationContext`。输出层通过 `GenerationTarget` 接口隔离，V1 使用 Freemarker 生成 Java CRUD 源码并以内存 ZIP 下载，绝不写入工作区源码。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis-Plus 3.5.5、Freemarker、MySQL 8、H2 2.2、JUnit 5、MockMvc

## Global Constraints

- V1 只生成 Entity、Mapper、Service、ServiceImpl、Controller 和 README，不生成前端或菜单 SQL。
- 所有生成器接口统一位于 `/system/generator`，且仅 `PLATFORM_ADMIN` 可访问。
- 生成器只读取当前业务库，不接受任意 JDBC URL、模板或输出目录。
- ZIP 只在内存中构建，不覆盖或删除现有源码。
- 元数据模型不得依赖 Freemarker 或前端组件名称。
- 仅支持单列主键；无主键或联合主键表明确拒绝。
- 主键策略仅允许 `AUTO`、`ASSIGN_ID`、`INPUT`。
- 数据范围仅允许 `NONE`、`BUILDING`；`BUILDING` 必须绑定真实 String 字段。
- 当前目录不是 Git 仓库，实施过程中以测试通过和文件清单作为任务检查点，不执行提交命令。

---

## File Map

### 新建生产代码

- `src/main/java/com/platform/generator/model/entity/GenTable.java`：表级生成配置。
- `src/main/java/com/platform/generator/model/entity/GenColumn.java`：字段级生成配置。
- `src/main/java/com/platform/generator/model/dto/GeneratorDtos.java`：导入、更新、预览及详情 DTO。
- `src/main/java/com/platform/generator/model/meta/GeneratorMetadata.java`：中立元数据记录类型。
- `src/main/java/com/platform/generator/mapper/GenTableMapper.java`：表配置 Mapper。
- `src/main/java/com/platform/generator/mapper/GenColumnMapper.java`：字段配置 Mapper。
- `src/main/java/com/platform/generator/metadata/JavaTypeMapper.java`：MySQL 到 Java 类型映射。
- `src/main/java/com/platform/generator/metadata/GeneratorNames.java`：名称转换和安全校验。
- `src/main/java/com/platform/generator/metadata/DatabaseMetadataReader.java`：数据库结构读取接口。
- `src/main/java/com/platform/generator/metadata/MysqlDatabaseMetadataReader.java`：基于 JDBC metadata 的实现。
- `src/main/java/com/platform/generator/service/GeneratorService.java`：生成器用例接口。
- `src/main/java/com/platform/generator/service/impl/GeneratorServiceImpl.java`：导入、更新、验证和上下文组装。
- `src/main/java/com/platform/generator/template/FreemarkerTemplateRenderer.java`：模板渲染边界。
- `src/main/java/com/platform/generator/target/GenerationTarget.java`：输出目标接口。
- `src/main/java/com/platform/generator/target/JavaZipGenerationTarget.java`：V1 Java 文件渲染。
- `src/main/java/com/platform/generator/support/ZipArchiveWriter.java`：安全内存 ZIP。
- `src/main/java/com/platform/generator/controller/GeneratorController.java`：管理员 REST API。

### 新建模板

- `src/main/resources/templates/generator/java/entity.java.ftl`
- `src/main/resources/templates/generator/java/mapper.java.ftl`
- `src/main/resources/templates/generator/java/service.java.ftl`
- `src/main/resources/templates/generator/java/serviceImpl.java.ftl`
- `src/main/resources/templates/generator/java/controller.java.ftl`
- `src/main/resources/templates/generator/java/README.md.ftl`

### 修改配置和数据库

- `pom.xml`：增加 MyBatis-Plus Generator 和 Freemarker 依赖。
- `src/env/init/03-init-hvac-schema.sql`：增加 `gen_table`、`gen_column`。
- `src/test/resources/schema-test.sql`：增加生成器测试表和配置表。
- `src/test/resources/application-test.yml`：关闭控制指令超时扫描。
- `src/main/java/com/platform/iot/service/impl/ControlCommandServiceImpl.java`：尊重控制功能开关，避免测试后台异常。

### 新建测试

- `src/test/java/com/platform/generator/JavaTypeMapperTest.java`
- `src/test/java/com/platform/generator/GeneratorNamesTest.java`
- `src/test/java/com/platform/generator/GeneratorServiceTest.java`
- `src/test/java/com/platform/generator/JavaZipGenerationTargetTest.java`
- `src/test/java/com/platform/GeneratorControllerFlowTest.java`

---

### Task 1: 清理测试环境控制定时任务噪声

**Files:**
- Modify: `src/main/java/com/platform/iot/service/impl/ControlCommandServiceImpl.java`
- Modify: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/platform/FourRoleBackendFlowTest.java`

**Interfaces:**
- Consumes: 配置项 `features.control-enabled`。
- Produces: `scanTimeoutCommands()` 在功能关闭时不访问 `control_commands`。

- [ ] **Step 1: 为现有测试增加日志捕获断言或运行基线测试**

Run:

```powershell
mvn -Dtest=FourRoleBackendFlowTest test
```

Expected: 测试退出码为 0，但当前日志出现 `Table "control_commands" not found`。

- [ ] **Step 2: 注入控制功能开关并在扫描入口提前返回**

在 `ControlCommandServiceImpl` 增加：

```java
@Value("${features.control-enabled:false}")
private boolean controlEnabled;

@Scheduled(fixedDelayString = "${features.control-timeout-scan-delay-ms:10000}")
public void scanTimeoutCommands() {
    if (!controlEnabled) {
        return;
    }
    // 保留现有查询与超时处理
}
```

同时删除原方法上的 `@Scheduled(fixedDelay = 10000)`，补充 `org.springframework.beans.factory.annotation.Value` 导入。

- [ ] **Step 3: 明确测试配置**

向 `application-test.yml` 增加：

```yaml
features:
  control-enabled: false
```

- [ ] **Step 4: 运行回归测试**

Run:

```powershell
mvn -Dtest=FourRoleBackendFlowTest test
```

Expected: PASS，日志不再出现 `control_commands not found`。

---

### Task 2: 增加依赖、DDL 与持久化实体

**Files:**
- Modify: `pom.xml`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Modify: `src/test/resources/schema-test.sql`
- Create: `src/main/java/com/platform/generator/model/entity/GenTable.java`
- Create: `src/main/java/com/platform/generator/model/entity/GenColumn.java`
- Create: `src/main/java/com/platform/generator/mapper/GenTableMapper.java`
- Create: `src/main/java/com/platform/generator/mapper/GenColumnMapper.java`

**Interfaces:**
- Produces: MyBatis-Plus 可读写的 `GenTable`、`GenColumn`，表主键均为 `Long` 自增。

- [ ] **Step 1: 增加生成器依赖并验证依赖解析**

向 `pom.xml` 增加：

```xml
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-generator</artifactId>
  <version>${mybatis-plus.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-freemarker</artifactId>
</dependency>
```

Run: `mvn -DskipTests compile`

Expected: BUILD SUCCESS。

- [ ] **Step 2: 在生产和测试 DDL 增加配置表**

使用完全一致的核心字段创建：

```sql
CREATE TABLE IF NOT EXISTS gen_table (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_name VARCHAR(128) NOT NULL UNIQUE,
  table_comment VARCHAR(255),
  module_name VARCHAR(64) NOT NULL,
  business_name VARCHAR(64) NOT NULL,
  class_name VARCHAR(128) NOT NULL,
  package_name VARCHAR(255) NOT NULL,
  id_type VARCHAR(32) NOT NULL DEFAULT 'INPUT',
  logic_delete_column VARCHAR(128),
  scope_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
  scope_column VARCHAR(128),
  read_roles VARCHAR(1000) NOT NULL,
  write_roles VARCHAR(1000) NOT NULL,
  generate_mode VARCHAR(32) NOT NULL DEFAULT 'JAVA_ZIP',
  status TINYINT NOT NULL DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS gen_column (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_id BIGINT NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  column_comment VARCHAR(255),
  jdbc_type VARCHAR(64) NOT NULL,
  java_type VARCHAR(128) NOT NULL,
  java_field VARCHAR(128) NOT NULL,
  is_primary_key TINYINT NOT NULL DEFAULT 0,
  is_nullable TINYINT NOT NULL DEFAULT 1,
  is_logic_delete TINYINT NOT NULL DEFAULT 0,
  is_list TINYINT NOT NULL DEFAULT 1,
  is_query TINYINT NOT NULL DEFAULT 0,
  query_type VARCHAR(32) NOT NULL DEFAULT 'EQ',
  is_edit TINYINT NOT NULL DEFAULT 1,
  is_required TINYINT NOT NULL DEFAULT 0,
  component_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  sort_order INT NOT NULL DEFAULT 0,
  UNIQUE (table_id, column_name)
);
```

- [ ] **Step 3: 实现实体与 Mapper**

实体使用 `@Data`、`@TableName`、`@TableId(type = IdType.AUTO)`，布尔配置统一使用 `Integer`，日期使用 `Date`。Mapper 形态固定为：

```java
@Mapper
public interface GenTableMapper extends BaseMapper<GenTable> {}
```

`GenColumnMapper` 使用相同结构。

- [ ] **Step 4: 编译确认映射完整**

Run: `mvn -DskipTests compile`

Expected: BUILD SUCCESS。

---

### Task 3: 实现中立元数据、命名规则与类型映射

**Files:**
- Create: `src/main/java/com/platform/generator/model/meta/GeneratorMetadata.java`
- Create: `src/main/java/com/platform/generator/metadata/GeneratorNames.java`
- Create: `src/main/java/com/platform/generator/metadata/JavaTypeMapper.java`
- Test: `src/test/java/com/platform/generator/GeneratorNamesTest.java`
- Test: `src/test/java/com/platform/generator/JavaTypeMapperTest.java`

**Interfaces:**
- Produces: `GeneratorNames.toCamelCase(String)`、`toPascalCase(String)`、`requireJavaIdentifier(String)`、`requirePackageName(String)`。
- Produces: `JavaTypeMapper.map(String jdbcType): String`。
- Produces: `GeneratorMetadata.TableMeta`、`ColumnMeta`、`PermissionMeta`、`DataScopeMeta`、`GenerationContext`。

- [ ] **Step 1: 先写命名和类型映射失败测试**

覆盖：

```java
assertThat(GeneratorNames.toCamelCase("rated_power")).isEqualTo("ratedPower");
assertThat(GeneratorNames.toPascalCase("biz_equipment")).isEqualTo("BizEquipment");
assertThatThrownBy(() -> GeneratorNames.requirePackageName("com.platform;drop"))
        .isInstanceOf(BusinessException.class);
assertThat(new JavaTypeMapper().map("decimal")).isEqualTo("java.math.BigDecimal");
assertThatThrownBy(() -> new JavaTypeMapper().map("geometry"))
        .isInstanceOf(BusinessException.class);
```

Run: `mvn -Dtest=GeneratorNamesTest,JavaTypeMapperTest test`

Expected: FAIL，因为类尚不存在。

- [ ] **Step 2: 实现安全名称工具**

规则：数据库名称 `^[A-Za-z][A-Za-z0-9_]*$`，Java 标识符使用 `SourceVersion.isIdentifier` 且拒绝关键字，包名按点拆分逐段校验；非法输入抛出 `BusinessException(400, ...)`。

- [ ] **Step 3: 实现明确类型映射**

使用不可变 Map，先去除 `unsigned`、长度和精度，再映射设计文档列出的类型。返回全限定类型名，模板根据类型集合生成 import。

- [ ] **Step 4: 定义中立 record 模型**

最小接口：

```java
public final class GeneratorMetadata {
    public record ColumnMeta(String columnName, String columnComment,
            String jdbcType, String javaType, String javaField,
            boolean primaryKey, boolean nullable, boolean logicDelete,
            boolean list, boolean query, String queryType,
            boolean edit, boolean required, String componentType, int sortOrder) {}

    public record PermissionMeta(List<String> readRoles, List<String> writeRoles) {}
    public record DataScopeMeta(String type, String columnName, String javaField) {}
    public record TableMeta(String tableName, String tableComment, String moduleName,
            String businessName, String className, String packageName, String idType,
            String logicDeleteColumn, PermissionMeta permissions,
            DataScopeMeta dataScope, List<ColumnMeta> columns) {}
    public record GenerationContext(TableMeta table, ColumnMeta primaryKey,
            Set<String> imports) {}
    private GeneratorMetadata() {}
}
```

- [ ] **Step 5: 运行单元测试**

Run: `mvn -Dtest=GeneratorNamesTest,JavaTypeMapperTest test`

Expected: PASS。

---

### Task 4: 实现数据库元数据读取与表导入

**Files:**
- Create: `src/main/java/com/platform/generator/metadata/DatabaseMetadataReader.java`
- Create: `src/main/java/com/platform/generator/metadata/MysqlDatabaseMetadataReader.java`
- Create: `src/main/java/com/platform/generator/model/dto/GeneratorDtos.java`
- Create: `src/main/java/com/platform/generator/service/GeneratorService.java`
- Create: `src/main/java/com/platform/generator/service/impl/GeneratorServiceImpl.java`
- Test: `src/test/java/com/platform/generator/GeneratorServiceTest.java`

**Interfaces:**
- `DatabaseMetadataReader.listTables(): List<DiscoveredTable>`。
- `DatabaseMetadataReader.readTable(String): DiscoveredTable`。
- `GeneratorService.importTable(ImportTableRequest): GeneratorConfigView`。
- `GeneratorService.detail(Long): GeneratorConfigView`。

- [ ] **Step 1: 写导入服务失败测试**

使用 H2 的 `biz_equipment` 验证：导入后存在一条 `gen_table`、对应列写入 `gen_column`、主键为 `equip_id`、`del_flag` 自动成为逻辑删除字段、默认角色为 `PLATFORM_ADMIN`。

Run: `mvn -Dtest=GeneratorServiceTest test`

Expected: FAIL，因为服务尚不存在。

- [ ] **Step 2: 定义 DTO 和接口**

`GeneratorDtos` 提供：

```java
public record ImportTableRequest(String tableName, String moduleName,
        String businessName, String className, String packageName) {}
public record ColumnConfig(Long id, String columnName, String columnComment,
        String jdbcType, String javaType, String javaField, boolean primaryKey,
        boolean nullable, boolean logicDelete, boolean list, boolean query,
        String queryType, boolean edit, boolean required,
        String componentType, int sortOrder) {}
public record GeneratorConfigView(Long id, String tableName, String tableComment,
        String moduleName, String businessName, String className,
        String packageName, String idType, String logicDeleteColumn,
        String scopeType, String scopeColumn, List<String> readRoles,
        List<String> writeRoles, List<ColumnConfig> columns) {}
```

- [ ] **Step 3: 基于 `DatabaseMetaData` 实现读取器**

使用 `DataSource.getConnection().getMetaData()` 的 `getTables`、`getColumns`、`getPrimaryKeys`，限制 catalog 为当前连接 catalog，排除 `gen_table`、`gen_column` 和四个系统 schema。所有 ResultSet 使用 try-with-resources。

- [ ] **Step 4: 实现事务导入**

`@Transactional` 导入流程必须：校验名称、拒绝重复导入、读取单列主键、映射全部字段、写 `gen_table` 后批量写 `gen_column`。角色 JSON 使用现有 Jackson `ObjectMapper`，固定默认值 `["PLATFORM_ADMIN"]`。

- [ ] **Step 5: 运行导入测试**

Run: `mvn -Dtest=GeneratorServiceTest test`

Expected: PASS。

---

### Task 5: 实现配置更新和生成上下文校验

**Files:**
- Modify: `src/main/java/com/platform/generator/model/dto/GeneratorDtos.java`
- Modify: `src/main/java/com/platform/generator/service/GeneratorService.java`
- Modify: `src/main/java/com/platform/generator/service/impl/GeneratorServiceImpl.java`
- Modify: `src/test/java/com/platform/generator/GeneratorServiceTest.java`

**Interfaces:**
- `GeneratorService.update(Long, UpdateGeneratorConfigRequest): GeneratorConfigView`。
- `GeneratorService.delete(Long): void`。
- `GeneratorService.buildContext(Long): GenerationContext`。

- [ ] **Step 1: 写配置校验失败测试**

覆盖：非法包名、空角色、未知角色、`BUILDING` 未指定字段、范围字段不存在、范围字段非 String、联合主键、删除不存在配置。

- [ ] **Step 2: 增加更新 DTO**

```java
public record UpdateGeneratorConfigRequest(String moduleName,
        String businessName, String className, String packageName,
        String idType, String logicDeleteColumn, String scopeType,
        String scopeColumn, List<String> readRoles, List<String> writeRoles,
        List<ColumnUpdate> columns) {}
```

`ColumnUpdate` 包含 `id`、`javaType`、`javaField`、列表/查询/编辑/必填、查询方式、控件类型和排序字段。

- [ ] **Step 3: 实现白名单校验**

固定角色集合：`BUILDING_OWNER`、`ENERGY_MANAGER`、`THIRD_PARTY`、`PLATFORM_ADMIN`。固定查询类型集合：`EQ`、`LIKE`、`BETWEEN`、`GT`、`GE`、`LT`、`LE`。固定控件集合：`TEXT`、`TEXTAREA`、`NUMBER`、`SELECT`、`DATE`、`DATETIME`、`SWITCH`。

- [ ] **Step 4: 实现上下文组装**

从持久化配置生成不可变 `GenerationContext`，校验唯一主键、主键策略、逻辑删除字段、数据范围字段和 Java import 集合。业务异常必须包含配置 ID 和具体原因。

- [ ] **Step 5: 运行服务测试**

Run: `mvn -Dtest=GeneratorServiceTest test`

Expected: PASS。

---

### Task 6: 实现 Freemarker 渲染和 Java 输出目标

**Files:**
- Create: `src/main/java/com/platform/generator/template/FreemarkerTemplateRenderer.java`
- Create: `src/main/java/com/platform/generator/target/GenerationTarget.java`
- Create: `src/main/java/com/platform/generator/target/JavaZipGenerationTarget.java`
- Create: `src/main/java/com/platform/generator/support/ZipArchiveWriter.java`
- Create: six templates under `src/main/resources/templates/generator/java/`
- Test: `src/test/java/com/platform/generator/JavaZipGenerationTargetTest.java`

**Interfaces:**
- `GenerationTarget.generate(GenerationContext): Map<String,String>`。
- `FreemarkerTemplateRenderer.render(String, Map<String,Object>): String`。
- `ZipArchiveWriter.write(Map<String,String>): byte[]`。

- [ ] **Step 1: 写输出目标失败测试**

构造 `biz_equipment` 上下文，断言输出恰好六个文件，Entity 含 `@TableId(type = IdType.INPUT)` 和 `@TableLogic`，Controller 含 `BuildingScopeService` 与角色表达式，ZIP 可被 `ZipInputStream` 完整读取。

- [ ] **Step 2: 实现模板渲染边界**

通过 Spring `freemarker.template.Configuration` 加载 classpath 模板，使用 UTF-8 和严格异常处理；模板异常包装为 `BusinessException(500, "代码模板渲染失败: " + templateName)`。

- [ ] **Step 3: 实现输出接口与路径计算**

Java 源文件根路径固定为：

```text
src/main/java/{packagePath}/{moduleName}/
```

Entity、Mapper、Service、Impl、Controller 分别放入现有项目对应子目录；README 放 ZIP 根目录。任何条目包含 `..`、以 `/` 开头或包含 `:` 时拒绝。

- [ ] **Step 4: 编写六个模板**

模板必须引用 `GenerationContext` 的中立字段，生成当前项目风格的 Lombok Entity、BaseMapper、IService、ServiceImpl、分页 CRUD Controller。`BUILDING` 模式生成建筑范围过滤，`NONE` 模式不导入相关服务。

- [ ] **Step 5: 实现安全 ZIP**

使用 `ByteArrayOutputStream`、`ZipOutputStream` 和 UTF-8 写入每个文本条目；对规范化路径重复校验且拒绝重复条目。

- [ ] **Step 6: 运行输出测试**

Run: `mvn -Dtest=JavaZipGenerationTargetTest test`

Expected: PASS。

---

### Task 7: 实现管理员预览与下载接口

**Files:**
- Create: `src/main/java/com/platform/generator/controller/GeneratorController.java`
- Modify: `src/main/java/com/platform/generator/service/GeneratorService.java`
- Modify: `src/main/java/com/platform/generator/service/impl/GeneratorServiceImpl.java`
- Test: `src/test/java/com/platform/GeneratorControllerFlowTest.java`

**Interfaces:**
- `GET /system/generator/tables`
- `POST /system/generator/import`
- `GET /system/generator/{id}`
- `PUT /system/generator/{id}`
- `DELETE /system/generator/{id}`
- `POST /system/generator/{id}/preview`
- `POST /system/generator/{id}/download`

- [ ] **Step 1: 写权限和下载失败测试**

使用现有登录辅助方式验证：未登录为 401，BUILDING_OWNER 为 403，PLATFORM_ADMIN 可以列表、导入、预览和下载；下载响应 `Content-Type` 为 `application/zip` 且 `Content-Disposition` 使用安全文件名。

- [ ] **Step 2: 实现 Controller**

类级声明：

```java
@RestController
@RequestMapping("/system/generator")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class GeneratorController { ... }
```

除下载外统一返回 `Result<T>`。下载使用 `ResponseEntity<byte[]>`，文件名为 `{businessName}-java.zip`。

- [ ] **Step 3: 复用同一生成核心**

预览与下载都调用 `GenerationTarget.generate(context)`；下载只额外调用 `ZipArchiveWriter.write(files)`，防止预览与下载内容分叉。

- [ ] **Step 4: 运行接口测试**

Run: `mvn -Dtest=GeneratorControllerFlowTest test`

Expected: PASS。

---

### Task 8: 编译生成源码并完成全量回归

**Files:**
- Modify: `src/test/java/com/platform/generator/JavaZipGenerationTargetTest.java`
- Modify: `src/test/java/com/platform/GeneratorControllerFlowTest.java`
- Modify: `docs/superpowers/specs/2026-07-22-backend-code-generator-v1-design.md` only if implementation reveals a concrete mismatch

**Interfaces:**
- Produces: 生成源码能使用 JDK 21 编译器和项目测试 classpath 编译。

- [ ] **Step 1: 增加 JavaCompiler 测试**

将输出 Map 中 `.java` 文件写入 JUnit `@TempDir`，使用：

```java
JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, UTF_8);
List<String> options = List.of("--release", "21", "-classpath", System.getProperty("java.class.path"));
boolean success = compiler.getTask(null, manager, null, options, null,
        manager.getJavaFileObjectsFromPaths(javaFiles)).call();
assertThat(success).isTrue();
```

- [ ] **Step 2: 运行生成器测试集**

Run:

```powershell
mvn -Dtest=JavaTypeMapperTest,GeneratorNamesTest,GeneratorServiceTest,JavaZipGenerationTargetTest,GeneratorControllerFlowTest test
```

Expected: 所有生成器测试 PASS。

- [ ] **Step 3: 运行全量后端测试**

Run: `mvn test`

Expected: BUILD SUCCESS，现有 4 组测试和新增生成器测试全部通过，日志无 `control_commands not found`。

- [ ] **Step 4: 验证打包**

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS，生成 `target/iot-platform-demo-1.0-SNAPSHOT.jar`。

- [ ] **Step 5: 最终检查**

确认：

```text
生成器未创建 src/main/java 下的任何业务生成结果
ZIP 恰好包含约定文件
没有任意模板上传和输出目录参数
所有名称和角色均经过白名单或语法校验
GenerationTarget 不依赖 Freemarker 具体类型
```

---

## Plan Self-Review

- 设计范围已分别覆盖持久化、元数据、读取、校验、渲染、ZIP、REST、安全和测试。
- 文件职责与接口签名在各任务之间一致。
- V1 未混入 Vue、菜单 SQL、动态 SQL、在线编译和源码覆盖。
- 生成源码编译测试是最终验收的一部分，不只验证字符串快照。
- 测试环境异步控制任务错误在首个任务解决，避免掩盖生成器回归问题。
