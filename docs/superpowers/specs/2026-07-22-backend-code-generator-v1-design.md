# 后端代码生成器 V1 设计

## 1. 目标

在现有 IoT/HVAC 平台中新增一个仅面向平台管理员的后端代码生成器。V1 从当前 MySQL 业务库读取表结构，将其转换成与模板引擎无关的统一元数据模型，再生成可下载的 Java CRUD 源码 ZIP。

本设计遵循长期演进原则：V1 生成源码，V2 运行 JSON Schema 配置，V3 通过拖拽编辑配置；三个版本共享同一套表、字段、权限和数据范围元数据。

## 2. 本期范围

V1 生成以下文件：

- Entity
- Mapper
- Service
- ServiceImpl
- Controller
- `README.md` 生成清单与接入说明

V1 提供以下后端能力：

- 查询当前业务库中可导入的表
- 将表结构导入 `gen_table`、`gen_column`
- 查询和修改生成配置
- 预览单个生成文件
- 下载完整 Java 源码 ZIP
- 删除生成配置

V1 不包含：

- Vue 页面、前端 API、路由和菜单 SQL
- 自动写入或覆盖项目源码
- 在线编译、自动重启服务
- JSON Schema 运行时渲染
- 拖拽设计器
- 复杂业务算法、多表事务和关联逻辑生成
- MQTT、TDengine、COP 计算代码生成

## 3. 架构

```text
MySQL INFORMATION_SCHEMA
        |
        v
DatabaseMetadataReader
        |
        v
统一元数据模型
  - TableMeta
  - ColumnMeta
  - PrimaryKeyMeta
  - PermissionMeta
  - DataScopeMeta
        |
        +--> gen_table / gen_column（持久化配置）
        |
        v
GenerationContext
        |
        v
GenerationTarget
        |
        +--> JavaZipGenerationTarget（V1）
        +--> VueGenerationTarget（后续）
        +--> JsonSchemaGenerationTarget（V2）
        |
        v
Freemarker 模板 -> 内存 ZIP 响应
```

核心边界：

- `DatabaseMetadataReader` 只负责读取数据库结构，不负责模板渲染。
- 元数据模型不包含 Freemarker、Ant Design Vue 等具体实现名称。
- `GenerationTarget` 负责一种输出形式，V1 只实现 Java ZIP。
- Controller 只负责参数校验、权限与 HTTP 响应，不拼装模板。
- 生成器不向 `src/main/java` 或其他工作目录写文件。

## 4. 模块结构

```text
com.platform.generator
├── controller
│   └── GeneratorController.java
├── model
│   ├── entity
│   │   ├── GenTable.java
│   │   └── GenColumn.java
│   ├── dto
│   │   ├── ImportTableRequest.java
│   │   ├── UpdateGeneratorConfigRequest.java
│   │   └── PreviewRequest.java
│   └── meta
│       ├── TableMeta.java
│       ├── ColumnMeta.java
│       ├── PermissionMeta.java
│       ├── DataScopeMeta.java
│       └── GenerationContext.java
├── mapper
│   ├── GenTableMapper.java
│   └── GenColumnMapper.java
├── metadata
│   ├── DatabaseMetadataReader.java
│   ├── MysqlDatabaseMetadataReader.java
│   └── JavaTypeMapper.java
├── service
│   ├── GeneratorService.java
│   └── impl/GeneratorServiceImpl.java
├── target
│   ├── GenerationTarget.java
│   └── JavaZipGenerationTarget.java
├── template
│   └── FreemarkerTemplateRenderer.java
└── support
    ├── GeneratorNames.java
    └── ZipArchiveWriter.java
```

模板目录：

```text
src/main/resources/templates/generator/java/
├── entity.java.ftl
├── mapper.java.ftl
├── service.java.ftl
├── serviceImpl.java.ftl
├── controller.java.ftl
└── README.md.ftl
```

## 5. 持久化模型

### 5.1 gen_table

`gen_table` 保存表级配置：

| 字段 | 含义 |
|---|---|
| `id` | 自增主键 |
| `table_name` | MySQL 表名，当前业务库内唯一 |
| `table_comment` | 表注释 |
| `module_name` | 模块名，如 `hvac` |
| `business_name` | 业务名，如 `equipment` |
| `class_name` | Java 类名 |
| `package_name` | 基础包名 |
| `id_type` | `AUTO`、`ASSIGN_ID`、`INPUT` |
| `logic_delete_column` | 逻辑删除列，可为空 |
| `scope_type` | `NONE` 或 `BUILDING` |
| `scope_column` | 建筑范围字段，`BUILDING` 时必填 |
| `read_roles` | JSON 字符串数组 |
| `write_roles` | JSON 字符串数组 |
| `generate_mode` | V1 固定为 `JAVA_ZIP`，为 V2 预留 |
| `status` | 0 停用、1 启用 |
| `create_time` | 创建时间 |
| `update_time` | 更新时间 |

### 5.2 gen_column

`gen_column` 保存字段级配置：

| 字段 | 含义 |
|---|---|
| `id` | 自增主键 |
| `table_id` | 关联 `gen_table.id` |
| `column_name` | 数据库列名 |
| `column_comment` | 字段注释 |
| `jdbc_type` | MySQL 类型 |
| `java_type` | Java 类型 |
| `java_field` | Java 属性名 |
| `is_primary_key` | 是否主键 |
| `is_nullable` | 是否可空 |
| `is_logic_delete` | 是否逻辑删除字段 |
| `is_list` | 为 V2 预留的列表显示配置 |
| `is_query` | 为 V2 预留的查询配置 |
| `query_type` | `EQ`、`LIKE`、`BETWEEN` 等中立枚举 |
| `is_edit` | 为 V2 预留的表单配置 |
| `is_required` | 是否必填 |
| `component_type` | `TEXT`、`NUMBER`、`SELECT`、`DATE` 等中立控件类型 |
| `sort_order` | 字段顺序 |

数据库表是结构事实来源，`gen_table`、`gen_column` 是生成行为的配置来源。重新导入表时按列名合并：更新数据库结构属性，保留人工配置的查询、控件、权限与数据范围设置。

## 6. 元数据规则

### 6.1 命名

- 表名和列名只接受字母、数字和下划线。
- 包名必须符合 Java 包名规则，默认 `com.platform`。
- 类名必须符合 Java 标识符规则。
- 下划线名称转换为小驼峰字段和大驼峰类名。
- 表前缀不自动删除；导入时由 `className` 明确决定，避免错误猜测。

### 6.2 Java 类型映射

首期支持：

| MySQL 类型 | Java 类型 |
|---|---|
| `tinyint`、`smallint`、`int` | `Integer` |
| `bigint` | `Long` |
| `decimal`、`numeric` | `BigDecimal` |
| `float` | `Float` |
| `double` | `Double` |
| `char`、`varchar`、`text`、`json` | `String` |
| `date`、`datetime`、`timestamp` | `Date` |
| `bit`、`boolean` | `Boolean` |
| `blob`、`binary`、`varbinary` | `byte[]` |

遇到未支持类型时拒绝生成并返回具体表名、列名和类型，不静默映射为 `Object`。

### 6.3 主键

- 没有主键的表拒绝生成。
- V1 仅支持单列主键；联合主键拒绝生成并说明原因。
- `id_type` 必须是 `AUTO`、`ASSIGN_ID` 或 `INPUT`。
- 导入时根据 `auto_increment` 推荐 `AUTO`；其他主键默认 `INPUT`，雪花策略由管理员显式改为 `ASSIGN_ID`。

### 6.4 逻辑删除

- 若存在 `del_flag`，默认设置为逻辑删除列。
- 逻辑删除字段生成 `@TableLogic`。
- 其他逻辑删除列必须显式配置且必须真实存在。

### 6.5 权限和数据范围

- 所有生成器管理接口只允许 `PLATFORM_ADMIN`。
- 生成代码的默认读写角色均为 `PLATFORM_ADMIN`。
- 读操作生成 `hasAnyRole(...)`。
- 写操作生成 `hasAnyRole(...)`；只有一个角色时可简化为 `hasRole(...)`。
- `scope_type=BUILDING` 时，`scope_column` 必须存在且映射为 String 类型字段。
- 建筑范围 Controller 注入 `BuildingScopeService`，列表查询传递可访问建筑 ID；详情查询在返回前检查建筑权限。
- `scope_type=NONE` 时不生成建筑权限代码。

## 7. 生成代码约定

- Entity 使用 Lombok `@Data`、`@TableName`、`Serializable`。
- Mapper 继承 `BaseMapper<Entity>`，不生成 XML。
- Service 继承 `IService<Entity>`。
- ServiceImpl 继承 `ServiceImpl<Mapper, Entity>`。
- Controller 使用 `Result<T>`、`IPage<T>`、`@Valid` 和构造器注入。
- 列表接口为 `GET /{businessName}/list`。
- 详情接口为 `GET /{businessName}/detail/{id}`。
- 新增接口为 `POST /{businessName}/add`。
- 修改接口为 `PUT /{businessName}/update`。
- 删除接口为 `DELETE /{businessName}/delete/{id}`。
- 默认列表支持 `page`、`size` 和 `keyword`，关键字只查询明确配置为 `is_query=1` 且 `query_type=LIKE` 的字符串字段。
- 生成代码必须使用当前项目的 Java 21、Spring Boot 3.2.4、MyBatis-Plus 3.5.5 约定。

## 8. API

统一前缀：`/system/generator`。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/tables` | 查询当前业务库可导入表 |
| POST | `/import` | 导入一张表及字段元数据 |
| GET | `/{id}` | 查询完整生成配置 |
| PUT | `/{id}` | 修改表级与字段级配置 |
| DELETE | `/{id}` | 删除生成配置，不删除业务表 |
| POST | `/{id}/preview` | 返回文件名到源码文本的映射 |
| POST | `/{id}/download` | 返回 `application/zip` |

所有接口使用 `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`。下载接口使用附件响应，其余接口使用 `Result<T>`。

## 9. 数据流

### 9.1 导入

1. 管理员提交表名。
2. 服务校验表名格式和当前业务库归属。
3. 从 `INFORMATION_SCHEMA.TABLES`、`COLUMNS`、`KEY_COLUMN_USAGE` 读取结构。
4. 映射统一元数据并校验主键和字段类型。
5. 在本地事务中保存 `gen_table`、`gen_column`。
6. 返回完整配置。

### 9.2 预览与下载

1. 按 ID 读取生成配置与字段。
2. 再次校验名称、主键、权限和数据范围。
3. 组装 `GenerationContext`。
4. 由 `GenerationTarget` 渲染文件。
5. 预览接口返回文本映射；下载接口将相同结果写入内存 ZIP。
6. 不创建工作区源文件，不执行编译和重启。

## 10. 错误处理

以下情况返回 `BusinessException(400, message)`：

- 非法表名、类名、包名或业务名
- 表不存在或不属于当前业务库
- 表没有主键或使用联合主键
- 存在未支持的数据库字段类型
- 主键策略不在允许集合内
- 逻辑删除列不存在
- 建筑范围列不存在或类型不兼容
- 角色列表为空、包含未知角色或包含非法标识符
- 模板渲染失败

数据库访问和 ZIP 写入异常由全局异常处理器转换为统一 500 响应，日志记录内部原因但不向响应暴露数据库密码、连接串或磁盘路径。

## 11. 安全

- 生成器只读取当前 `spring.datasource` 指向的业务库。
- 查询 `INFORMATION_SCHEMA` 时使用参数绑定，不拼接用户输入 SQL。
- 排除 `information_schema`、`mysql`、`performance_schema`、`sys`。
- 生成配置接口和下载接口仅限 `PLATFORM_ADMIN`。
- ZIP 条目路径由服务端根据已校验的包名和类名计算，禁止 `..`、绝对路径和反斜杠逃逸。
- 不提供任意模板上传功能。
- 不提供任意输出目录功能。
- 不覆盖或删除现有源码。

## 12. 测试策略

### 12.1 单元测试

- MySQL 类型到 Java 类型映射。
- 下划线命名转换。
- 非法名称拒绝。
- 无主键、联合主键拒绝。
- 三种主键策略模板输出。
- `@TableLogic` 输出。
- 无数据范围与建筑数据范围模板输出。
- 单角色、多角色权限表达式输出。
- ZIP 条目路径与内容。

### 12.2 集成测试

- 使用 H2 测试元数据导入、配置更新、预览与下载。
- PLATFORM_ADMIN 可以调用全部生成接口。
- BUILDING_OWNER、ENERGY_MANAGER、THIRD_PARTY 和未登录用户被拒绝。
- 生成 ZIP 包含六个预期文件。
- 使用 JDK `JavaCompiler` 和测试运行时 classpath 编译生成源码，确保生成代码语法及项目依赖正确。
- 现有四组测试继续通过。
- 测试 profile 禁用控制指令超时扫描，避免缺少 `control_commands` 表产生异步错误日志。

## 13. 版本演进

V1 后续至少用 3 至 5 张真实业务表验证。进入 V2 前增加 JSON Schema 预览目标，检查当前元数据能否完整描述字段、查询、权限、数据范围和中立控件。

V2 新增 `JsonSchemaGenerationTarget`、通用 CRUD 白名单服务和通用页面运行时，不删除 V1。复杂页面继续选择源码生成或手写模式。

V3 只负责通过拖拽修改 V2 使用的 JSON Schema，不重新定义表、字段和权限模型。

## 14. 验收标准

- 平台管理员可以导入一张符合约束的 MySQL 表。
- 导入结果持久化到 `gen_table`、`gen_column`。
- 可以修改主键、权限、数据范围和字段生成配置。
- 预览和下载使用相同的生成核心。
- ZIP 包含 Entity、Mapper、Service、ServiceImpl、Controller 和 README。
- 生成源码通过 Java 21 编译验证。
- 生成器不修改工作区源文件。
- 非平台管理员不能访问生成器。
- 现有测试全部通过，测试日志不再出现控制指令表缺失异常。
- 核心元数据模型不依赖 Freemarker，为 V2/V3 保留扩展边界。
