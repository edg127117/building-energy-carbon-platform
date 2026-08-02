# Java 21 后端开发环境

> **文档状态：当前开发指南**
>
> 本文只说明仓库要求和当前终端配置方法，不记录某台电脑的安装盘符，也不会替用户修改机器级环境变量。

## 统一版本

- JDK：Java 21，厂商不限；仓库 `.java-version` 和 CI 使用 Java 21。
- Maven：通过仓库根目录 Maven Wrapper 下载并使用 Maven 3.9.9；Wrapper 工具版本为 3.3.4。
- Windows 构建：`.\mvnw.cmd verify`。
- Linux、macOS 和 CI 构建：`./mvnw verify`。

`pom.xml` 会在编译和测试前检查版本：Java 必须处于 `[21,22)`，Maven 必须处于
`[3.9.9,4.0.0)`。应使用仓库 Wrapper，不依赖开发机另行安装的 Maven。

## Windows 当前终端配置

把示例路径替换为本机 JDK 21 根目录：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\scripts\check-java21.ps1
.\mvnw.cmd -version
```

这些命令只影响当前 PowerShell 进程。若通过系统设置或 IDE 配置永久调整了
`JAVA_HOME`，需要重新打开终端、IDE 和 Codex，让新进程读取更新后的环境。

## Linux 与 macOS 当前终端配置

Linux 使用实际安装路径：

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -version
```

macOS 可以让系统定位已安装的 JDK 21：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -version
```

## 验证

Windows：

```powershell
java -version
javac -version
.\mvnw.cmd -version
.\scripts\check-java21.ps1
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Linux、macOS：

```bash
java -version
javac -version
./mvnw -version
./mvnw --batch-mode --no-transfer-progress verify
```

`java`、`javac` 和 Wrapper 输出必须使用 Java 21，完整构建必须显示 `BUILD SUCCESS`。
Windows 检查脚本还会核对 `JAVA_HOME`、PATH、`javac` 和 Wrapper 所用 Java 是否一致。

## 常见错误

- `release version 21 not supported`：Maven 正在使用低于 21 的 JDK，检查 `JAVA_HOME`。
- `UnsupportedClassVersionError`：运行时 `java` 仍是旧版本，检查 PATH 顺序。
- `RequireJavaVersion`：当前 JDK 主版本不是 21。
- `RequireMavenVersion`：没有通过仓库 Maven Wrapper 执行构建。
- Wrapper 校验失败：下载内容与固定 SHA-256 不一致，不要绕过校验。
