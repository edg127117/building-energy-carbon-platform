# HVAC API Path Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 HVAC 查询接口只通过正确的 `/api/hvac/**` 外部路径访问，并彻底废弃重复的 `/api/api/hvac/**` 路径。

**Architecture:** 保留 Spring Boot 全局 `server.servlet.context-path: /api`，Controller 只声明模块内部路径 `/hvac/buildings`。流程测试显式模拟 `/api` context path，使 MockMvc 的请求拆分与真实服务器一致。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring MVC、Spring Security、JUnit 5、MockMvc、Maven Wrapper 3.9.9

## Global Constraints

- 错误路径 `/api/api/hvac/**` 直接废弃，不提供别名、重定向或代理兼容。
- 不修改 Service、Repository、数据库结构、权限规则、前端请求封装、Nginx 配置或其他业务接口。
- 保持快照和历史查询的参数、权限、响应结构及异常语义不变。
- 使用 `mvnw.cmd` 执行 Java 21 定向测试和完整回归。

---

### Task 1: 修正 HVAC Controller 路径并建立真实 context path 回归测试

**Files:**
- Modify: `src/test/java/com/platform/HvacQueryControllerFlowTest.java`
- Modify: `src/main/java/com/platform/hvac/controller/HvacQueryController.java`

**Interfaces:**
- Consumes: Spring Boot 全局配置 `server.servlet.context-path=/api`
- Produces: `GET /api/hvac/buildings/{buildingId}/snapshot`
- Produces: `GET /api/hvac/buildings/{buildingId}/history`
- Removes: `/api/api/hvac/**`

- [ ] **Step 1: 先把流程测试改成真实服务器的 context path 语义**

在 `HvacQueryControllerFlowTest` 增加导入：

```java
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
```

在测试类底部增加统一的 HVAC GET 请求构造方法：

```java
private MockHttpServletRequestBuilder hvacGet(String path) {
    // 外部 URI 包含 /api，contextPath 会在进入 Controller 映射前被 Spring MVC 剥离。
    return get("/api/hvac/buildings" + path).contextPath("/api");
}
```

把所有 HVAC 正常请求从：

```java
get("/api/hvac/buildings/BLD001/snapshot")
```

改为：

```java
hvacGet("/BLD001/snapshot")
```

历史查询、未知建筑和权限测试使用同一方法，只替换末尾业务路径，现有
`header(...)` 与 `param(...)` 调用保持不变。

增加错误路径回归测试：

```java
@Test
void duplicatedApiPrefixIsNotExposed() throws Exception {
    mockMvc.perform(get("/api/api/hvac/buildings/BLD001/snapshot")
                    .contextPath("/api")
                    .header(auth(), bearer(adminToken)))
            .andExpect(status().isNotFound());
}
```

- [ ] **Step 2: 运行定向测试，确认旧 Controller 映射无法满足新契约**

Run:

```powershell
$env:JAVA_HOME='F:\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=HvacQueryControllerFlowTest test
```

Expected: FAIL。正确的 `/api/hvac/**` 请求在剥离 context path 后变成
`/hvac/**`，而旧 Controller 仍映射 `/api/hvac/**`；重复路径测试也会发现旧路径
仍然可访问。

- [ ] **Step 3: 最小修改 Controller 内部映射**

把 `HvacQueryController` 的类级映射改为：

```java
@RestController
@RequestMapping("/hvac/buildings")
@RequiredArgsConstructor
public class HvacQueryController {
```

在类级注释中补充路径边界原因：

```java
/**
 * HVAC 冻结分钟数据的只读 HTTP 入口。
 *
 * <p>这里仅声明模块内部路径 {@code /hvac/buildings}；对外统一的
 * {@code /api} 前缀由 {@code server.servlet.context-path} 提供，避免形成
 * {@code /api/api} 重复路径。</p>
 *
 * <p>Controller 只负责接收请求参数、提取当前登录用户身份并包装统一响应；
 * 建筑范围、测点归属、时间跨度和 TDengine 异常转换统一由
 * {@link HvacQueryService} 处理。角色注解是第一层入口限制，Service 中的建筑范围
 * 校验是第二层数据权限限制。</p>
 */
```

- [ ] **Step 4: 运行 HVAC Controller 定向测试**

Run:

```powershell
$env:JAVA_HOME='F:\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=HvacQueryControllerFlowTest test
```

Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`，包含正确路径、
权限规则、参数校验、404、503 和重复 `/api` 路径回归。

- [ ] **Step 5: 执行完整后端回归和打包**

Run:

```powershell
$env:JAVA_HOME='F:\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Expected: `Tests run: 78, Failures: 0, Errors: 0, Skipped: 0`，并成功生成
`target/iot-platform-demo-1.0-SNAPSHOT.jar`。

- [ ] **Step 6: 检查范围并提交修复**

Run:

```powershell
git diff --check
git status --short
git add -- src/main/java/com/platform/hvac/controller/HvacQueryController.java src/test/java/com/platform/HvacQueryControllerFlowTest.java docs/superpowers/plans/2026-07-27-hvac-api-path.md
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(hvac): remove duplicated API path prefix"
```

Expected: 暂存区只包含 Controller、对应流程测试和本实施计划；提交成功且没有
空白错误。

- [ ] **Step 7: 推送任务分支并检查 GitHub CI**

Run:

```powershell
git push -u origin fix/hvac-api-path
```

Expected: 远程任务分支创建成功，Backend CI 的 Java 21 verify 检查通过。
