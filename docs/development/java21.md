# Java 21 后端开发环境

## 统一版本

- JDK：Java 21，厂商不限。
- Maven：通过仓库根目录的 Maven Wrapper 使用3.9.9。
- Windows构建：`.\mvnw.cmd verify`
- Linux、macOS和CI构建：`./mvnw verify`

`pom.xml` 会在编译和测试前检查版本：Java必须处于 `[21,22)`，Maven必须处于
`[3.9.9,4.0.0)`。请优先使用Wrapper，不要依赖开发机单独安装的Maven。

## Windows配置

1. 将 `JAVA_HOME` 指向JDK 21根目录。
2. 将 `%JAVA_HOME%\bin` 放在旧Java、Oracle `javapath` 等入口之前。
3. 关闭并重新打开终端、IDE和Codex，使新环境变量进入进程。
4. 在仓库根目录执行 `.\scripts\check-java21.ps1`。

当前开发机已经安装 `F:\jdk21`，只需把 `F:\jdk21\bin` 移到
`C:\Program Files\Common Files\Oracle\Java\javapath` 之前。其他开发机应使用自己的
JDK 21安装路径，不需要采用相同盘符。

## 验证

执行：

```powershell
java -version
javac -version
.\mvnw.cmd -version
.\scripts\check-java21.ps1
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

前三条命令必须显示Java 21，检查脚本必须返回退出码0，完整构建必须显示
`BUILD SUCCESS`。

## 常见错误

- `release version 21 not supported`：Maven正在使用低于21的JDK，检查 `JAVA_HOME`。
- `UnsupportedClassVersionError`：运行时 `java` 仍是旧版本，检查PATH顺序。
- `RequireJavaVersion`：当前JDK主版本不是21。
- `RequireMavenVersion`：没有通过仓库的Maven Wrapper执行构建。
- Wrapper校验失败：下载内容与固定SHA-256不一致，不要绕过校验。
