# Java 21 开发与后端 CI 统一设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 目标

把后端开发和持续集成统一到 Java 21 与 Maven 3.9.9，消除“`JAVA_HOME` 使用 Java 21、但 `java` 和 `javac` 命令仍使用 Java 17”的环境歧义，并确保每个面向 `main` 的 Pull Request 都自动完成后端测试和打包验证。

## 当前状态

- `pom.xml` 已声明 `<java.version>21</java.version>`。
- 本机已安装 Microsoft OpenJDK 21.0.11，`JAVA_HOME` 指向 `F:\jdk21`。
- 本机 Maven 3.9.9 实际使用 Java 21。
- 系统 `Path` 中 Oracle Java 入口位于 `F:\jdk21\bin` 之前，因此直接执行 `java` 和 `javac` 时仍得到 Java 17。
- 仓库没有 Maven Wrapper、Java 版本提示文件或 GitHub Actions 后端 CI。
- 普通后端测试已有 H2 和外部资源关闭配置，本次 CI 不启动真实 MySQL、TDengine、MQTT 或 Redis。

## 范围

本次包含：

1. 固定仓库支持的 Java 主版本为 21。
2. 固定项目构建入口使用 Maven Wrapper 3.9.9。
3. 在 Maven 构建最早阶段校验 Java 和 Maven 版本。
4. 提供 Windows 本地环境检查脚本和开发说明。
5. 增加 GitHub Actions 后端 CI，自动执行完整 Maven `verify`。
6. 在当前机器上说明并验证 Java 21 的正确 PATH 顺序。

本次不包含：

- 不修改任何业务代码、数据库结构或接口行为。
- 不修改前端依赖、构建、Vitest 或前端 CI。
- 不安装或替换本机 JDK；本机现有 JDK 21 已满足要求。
- 不由仓库脚本自动改写系统级环境变量，避免覆盖开发者已有 PATH。
- 不引入 Docker、Dev Container、Jenkins、SonarQube 或制品发布。

## 方案选择

采用“版本约束 + Maven Wrapper + GitHub Actions”的方案。

只增加 GitHub Actions 无法解决本地 `java`、`javac` 和 Maven使用不同JDK的问题；使用容器统一环境虽然隔离更彻底，但会为当前V1阶段增加镜像、IDE和容器维护成本。Maven Wrapper配合构建期校验能够以较低成本覆盖本地和CI两类入口。

## 仓库改动

### Java 版本提示

新增 `.java-version`，内容固定为：

```text
21
```

支持该文件的IDE和Java版本管理工具可自动选择Java 21。不支持该文件的Windows环境仍按开发文档配置 `JAVA_HOME` 和 `Path`。

### Maven Wrapper

新增官方Maven Wrapper脚本和配置：

```text
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.jar
.mvn/wrapper/maven-wrapper.properties
```

Wrapper下载并使用Apache Maven 3.9.9。Windows统一执行：

```powershell
.\mvnw.cmd verify
```

Linux和GitHub Actions统一执行：

```bash
./mvnw verify
```

Wrapper使用官方 `bin` 启动类型，提交63 KB的官方启动JAR，但不提交Maven安装包。之所以不使用
`only-script`，是因为Wrapper 3.3.4的Windows PowerShell 5脚本会对普通非符号链接的
`.m2`目录访问空 `Target`，导致启动阶段失败。Wrapper JAR和Apache Maven分发包都配置
对应SHA-256校验值，避免下载内容被静默替换。

### Maven版本门禁

在 `pom.xml` 中增加 `maven-enforcer-plugin`，绑定到 `validate` 阶段。

Java版本范围固定为：

```text
[21,22)
```

Maven版本范围固定为：

```text
[3.9.9,4.0.0)
```

这样可以：

- 接受任意Java 21补丁版本和JDK发行商；
- 拒绝Java 17、Java 22及其他未经验证的主版本；
- 接受Maven 3.9.9及后续3.9.x修订版本；
- 暂不接受存在兼容性变化的Maven 4。

版本不满足时，构建在编译和测试前失败，并用中文消息说明项目要求和当前版本。

## Windows本地环境

新增只读检查脚本：

```text
scripts/check-java21.ps1
```

脚本检查：

1. `JAVA_HOME` 是否存在。
2. `$env:JAVA_HOME\bin\java.exe` 是否存在且为Java 21。
3. PATH解析到的 `java` 是否为Java 21。
4. PATH解析到的 `javac` 是否为Java 21。
5. `.\mvnw.cmd -version` 是否使用Java 21和Maven 3.9.9。

检查全部通过时返回退出码0；任何一项不一致时返回非0，并明确输出错误项。脚本不修改注册表、用户环境变量或系统环境变量。

开发文档说明当前机器只需要把：

```text
F:\jdk21\bin
```

移动到：

```text
C:\Program Files\Common Files\Oracle\Java\javapath
```

之前，然后重新打开终端、IDE和Codex。通用说明使用 `%JAVA_HOME%\bin`，不要求其他开发者使用相同磁盘路径。

## GitHub Actions 后端 CI

新增：

```text
.github/workflows/backend-ci.yml
```

触发条件：

- 创建或更新目标为 `main` 的 Pull Request；
- 推送到 `main`；
- 手工触发 `workflow_dispatch`。

流水线行为：

1. 使用最小权限 `contents: read`。
2. 检出当前提交。
3. 使用Eclipse Temurin Java 21。
4. 启用Maven依赖缓存。
5. 执行 `./mvnw --batch-mode --no-transfer-progress verify`。
6. 不启动真实MySQL、TDengine、MQTT或Redis服务。

同一分支产生新提交时取消旧的未完成流水线，避免重复占用执行资源。

CI只负责验证，不自动提交文件、不发布制品、不部署环境，也不代替用户创建或合并PR。

## 错误处理

- Java不是21：Maven Enforcer在 `validate` 阶段失败。
- Maven不在允许范围：Maven Enforcer在 `validate` 阶段失败。
- Wrapper下载失败或校验失败：构建失败，不回退到系统Maven。
- 单元测试或打包失败：CI失败，任务分支不得作为可合并PR交付。
- 普通测试尝试依赖真实外部资源并因此失败：视为测试隔离缺陷，不在CI中启动真实服务绕过。
- 本机PATH仍优先解析Java 17：检查脚本失败并提示调整PATH顺序。

## 验证标准

1. `java -version`、`javac -version` 和 `.\mvnw.cmd -version` 均显示Java 21。
2. `.\scripts\check-java21.ps1` 返回退出码0。
3. `.\mvnw.cmd --batch-mode --no-transfer-progress verify` 完整通过。
4. 使用Java 17运行 `.\mvnw.cmd validate` 时，在Enforcer阶段明确失败。
5. Maven Wrapper实际使用Maven 3.9.9。
6. GitHub Actions工作流语法有效，面向 `main` 的PR会运行后端CI。
7. CI不配置真实MySQL、TDengine、MQTT或Redis服务。
8. 前端文件和业务代码无改动。

## 风险与控制

- Maven Wrapper首次执行需要下载Maven：通过固定版本和SHA-256校验控制供应链风险，并利用GitHub Actions缓存减少重复下载。
- 严格限制Java主版本可能导致已安装Java 22的开发者无法构建：这是有意门禁，避免未验证版本产生差异。
- 系统PATH调整需要重新打开进程才能生效：文档明确说明终端、IDE和Codex均需重启。
- CI首次启用可能暴露测试对外部资源的隐式依赖：应修复测试隔离，不通过给CI增加真实生产依赖来规避。

## 完成边界

完成开发并推送任务分支后，AI提供GitHub Compare链接、PR标题、说明和测试结果，状态为“等待用户创建并合并PR”。只有用户明确通知“已合并”后，才同步本地 `main` 并清理任务分支。
