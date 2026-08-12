# HVAC WebSocket 实时推送、断线重连与 HTTP 降级实施计划

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 HVAC 指标广播端点收敛为 JWT 认证、按建筑路由的实时通道，并让 Vue 大屏在断线时自动使用 HTTP 保障、重连后完成权威对账。

**Architecture:** 后端保留 Jakarta WebSocket 端点，但把协议解析、JWT/建筑权限判断和线程安全会话注册拆成独立组件；公式发布器把 `buildingId` 显式交给定向网关，不再全局广播。前端以无 Vue 依赖的 `HvacRealtimeClient` 管理单次连接、首帧订阅和 PING/PONG，`useHvacDashboard` 统一编排首次 HTTP、指标增量、500 毫秒合并对账、30 秒降级轮询和 1/2/5/10/30 秒重连。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring Security、Jakarta WebSocket、JJWT、Redis、MySQL、JUnit 5、Mockito、Vue 3、TypeScript 5、Vitest、Axios、原生 WebSocket、Docker Compose。

## Global Constraints

- 当前设计基线是 `docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md`；实现不得恢复匿名业务广播或永久双通道轮询。
- 通过 Codex 新任务创建全新工作树，不设置 `startingState`，从项目默认 `main` 创建独立任务分支；本地 `feature/hvac-realtime-resilience` 只作为已确认设计与计划的只读资料来源，不切换、不合并、不变基、不 cherry-pick。
- 开始生产代码前完整读取 `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md`、`docs/development/repository-guardrails.md`、`docs/development/code-comments.md` 和 `.agents/skills/iot-change-verification/SKILL.md`。
- 生产代码修改必须使用 `code-comment-quality` Skill；所有文件变化必须使用 `iot-change-verification`；Git、提交、推送和 PR 必须使用 `safe-pr-delivery`。
- V1 保持 Spring Boot 单体和 Vue 前端，不引入 STOMP、SockJS、Kafka、Redis Pub/Sub、独立实时服务或多实例会话同步。
- 握手可以匿名，但 `SUBSCRIBE` 成功前不得发送任何 `HVAC_INDICATOR`；JWT 不得进入 URL、日志、错误响应或仓库配置。
- WebSocket 只推送现有四项指标状态；19 测点完整状态继续由受保护 HTTP 快照提供。
- HTTP 是完整当前状态的权威读取入口；WebSocket 是最佳努力增量通知，发送失败不得回滚 TDengine 或 Redis。
- 前端不得重算指标、伪造测点、填默认业务值或修改 Q0/Q1/Q2 语义。
- 普通测试不得连接真实 MySQL、TDengine、Redis、EMQX 或现场设备；真实环境检查只能在专用 Docker 冒烟阶段执行并单独记录。
- 暂存必须显式列出路径，禁止 `git add .`；不得绕过 Hook、强推或直接推送 `main`。
- 本任务不修改数据库表结构、MQTT 上行、分钟聚合、公式算法、历史趋势或计算详情契约。

---

## File Structure

### Backend files to create

- `src/main/java/com/platform/iot/websocket/HvacRealtimeProtocol.java`
  - 解析 `SUBSCRIBE`/`PING`，序列化 `SUBSCRIBED`/`PONG`/`ERROR`，限制消息尺寸并集中保存协议常量。
- `src/main/java/com/platform/iot/websocket/HvacRealtimeAccessException.java`
  - 携带稳定错误码、脱敏文案和 WebSocket 应用关闭码。
- `src/main/java/com/platform/iot/websocket/HvacRealtimeSubscription.java`
  - 保存已验证用户、正式角色、建筑、Token 和 JWT 过期时间，仅存在于会话生命周期。
- `src/main/java/com/platform/iot/websocket/HvacRealtimeAccessService.java`
  - 复用 `JwtService`、`TokenCacheService` 和 `BuildingScopeService` 完成首次订阅与心跳复核。
- `src/main/java/com/platform/iot/websocket/HvacRealtimeSessionRegistry.java`
  - 管理 pending/authorized Session、建筑索引、订阅和心跳超时、串行发送与幂等清理。
- `src/main/java/com/platform/config/HvacRealtimeConfig.java`
  - 只装配实时超时使用的单线程 daemon `TaskScheduler`，不承载业务判断。

### Backend files to modify

- `src/main/java/com/platform/iot/websocket/WebSocketServer.java`
  - 从静态全局广播改为无会话字段的协议入口，使用 `SpringConfigurator` 获得 Spring 依赖。
- `src/main/java/com/platform/iot/websocket/RealtimeMessageGateway.java`
  - `broadcast(String)` 改为 `sendToBuilding(String, String)`。
- `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`
  - 委托 `HvacRealtimeSessionRegistry.sendToBuilding`。
- `src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java`
  - 显式把 `IndicatorLatestState.buildingId()` 交给网关。
- `src/main/java/com/platform/security/SecurityConfig.java`
  - 保持握手路由 permitAll，但更新注释，明确首帧认证才是业务权限边界。
- `src/main/java/com/platform/config/WebSocketConfig.java`
  - 保留非测试 Profile 的 `ServerEndpointExporter`，更新端点装配说明。

### Backend tests to create or modify

- Create `src/test/java/com/platform/iot/websocket/HvacRealtimeProtocolTest.java`
- Create `src/test/java/com/platform/iot/websocket/HvacRealtimeAccessServiceTest.java`
- Create `src/test/java/com/platform/iot/websocket/HvacRealtimeSessionRegistryTest.java`
- Create `src/test/java/com/platform/iot/websocket/WebSocketServerTest.java`
- Modify `src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java`
- Modify `src/test/java/com/platform/AppTest.java` only if the new scheduler bean requires a narrow context assertion; do not add real network startup.

### Frontend files to create

- `web/src/auth/session.ts`
  - HTTP 和 WebSocket 共用的浏览器登录失效清理。
- `web/src/auth/session.test.ts`
- `web/src/realtime/hvacRealtimeProtocol.ts`
  - 实时消息 TypeScript 类型、运行时解析、关闭码和 URL 生成。
- `web/src/realtime/hvacRealtimeProtocol.test.ts`
- `web/src/realtime/hvacRealtimeClient.ts`
  - 单连接、首帧订阅、订阅确认、PING/PONG 和主动关闭。
- `web/src/realtime/hvacRealtimeClient.test.ts`

### Frontend files to modify

- `web/src/utils/request.ts`
  - HTTP 401 调用共用会话失效函数。
- `web/src/types/hvac.ts`
  - 增加 WebSocket 指标 DTO，不改变现有 HTTP DTO。
- `web/src/domain/hvacDashboard.ts`
  - 增加只接受当前建筑且不回退分钟的指标增量合并。
- `web/src/domain/hvacDashboard.test.ts`
- `web/src/composables/useHvacDashboard.ts`
  - 增加实时状态机、合并对账、轮询切换、重连和资源清理。
- `web/src/composables/useHvacDashboard.test.ts`
- `web/src/pages/HvacDemoPage.vue`
  - 展示真实连接状态并绑定统一实时生命周期。
- `web/src/pages/HvacDemoPage.contract.test.ts`
- `web/.env.example`
  - 增加非敏感 `VITE_WS_BASE` 示例。

### Smoke, docs, and project state

- Create `scripts/Test-HvacRealtimeSmoke.ps1`
  - 在已启动的专用环境中登录、准备受限账号和第二建筑测试授权、运行有权/无权/跨建筑 WebSocket 检查；不删除数据。
- Create `.scripts/test-hvac-realtime.mjs`
  - 使用 Node 原生 `fetch`/`WebSocket` 执行多连接消息断言；Token 仅通过进程环境变量传入且不得输出。
- Modify `scripts/Invoke-CleanHvacSmoke.ps1`
  - 在原有受控 `-ResetData` 流程中调用新的实时冒烟脚本，并保持路径删除保护。
- Create `src/test/java/com/platform/config/HvacRealtimeSmokeScriptContractTest.java`
  - 静态验证脚本没有 Token URL、宽泛删除或旧电表路径。
- Modify `docs/MQTT-硬件数据对接说明.md`
- Modify `PROJECT_GUIDE.md`
- Modify `PROJECT_STATUS.md`
- Create `docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md`
  - 在代码与验证进入交付阶段后，把已确认设计原样纳入同一个功能 PR，不单独创建文档 PR。
- Create `docs/superpowers/plans/2026-08-10-hvac-realtime-websocket-resilience.md`
  - 保存已确认实施步骤；实际完成情况仍以代码、测试和 `PROJECT_STATUS.md` 为准。
- Modify `docs/superpowers/README.md`

---

### Task 0: Verify the Fresh Codex Worktree Baseline and Re-run Preflight

**Files:**
- Read: `AGENTS.md`
- Read: `PROJECT_GUIDE.md`
- Read: `PROJECT_STATUS.md`
- Read from planning checkout or local planning ref: `docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md`
- Read from planning checkout or local planning ref: `docs/superpowers/plans/2026-08-10-hvac-realtime-websocket-resilience.md`
- Read: `docs/development/repository-guardrails.md`
- Read: `docs/development/code-comments.md`
- Read: `.agents/skills/iot-change-verification/SKILL.md`

**Interfaces:**
- Consumes: project default `main` as the code baseline, plus read-only approved material from local branch `feature/hvac-realtime-resilience`.
- Produces: a clean, guarded Codex worktree on a new independent task branch with no inherited implementation diff.

- [ ] **Step 1: Inspect the Codex-created worktree without changing files**

Run:

```powershell
git status --short --branch
git branch --show-current
git log -3 --oneline --decorate
git worktree list --porcelain
```

Expected:

- no modified or untracked files;
- the current branch is neither empty nor `main`;
- HEAD starts from the project default main baseline;
- `feature/hvac-realtime-resilience` is not checked out, merged, rebased or cherry-picked.

- [ ] **Step 2: Stop if the worktree has no task branch**

Run:

```powershell
$taskBranch = git branch --show-current
if ([string]::IsNullOrWhiteSpace($taskBranch) -or $taskBranch -eq 'main') {
  throw 'Codex 新工作树未创建独立任务分支，请在 Codex 中重新创建工作树任务'
}
```

Expected: no output or error. Do not repair this state with manual `git worktree` commands.

- [ ] **Step 3: Confirm the branch is based on the known remote main**

Run:

```powershell
git merge-base --is-ancestor origin/main HEAD
if ($LASTEXITCODE -ne 0) { throw '新任务分支不包含项目默认 main 基线' }
```

Expected: exit code `0`. This verifies the fresh code baseline; approved design material is read-only input rather than Git ancestry.

- [ ] **Step 4: Run repository task preflight**

From the repository root, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

Expected: output contains `TASK_PREFLIGHT_OK`.

- [ ] **Step 5: Record the exact starting diff boundary**

From the repository root, run:

```powershell
git diff --name-only origin/main...HEAD
```

Expected: empty output. Any starting diff is a blocker and must be reported before coding.

---

### Task 1: Define and Test the Realtime Protocol and Access Decision

**Files:**
- Create: `src/main/java/com/platform/iot/websocket/HvacRealtimeProtocol.java`
- Create: `src/main/java/com/platform/iot/websocket/HvacRealtimeAccessException.java`
- Create: `src/main/java/com/platform/iot/websocket/HvacRealtimeSubscription.java`
- Create: `src/main/java/com/platform/iot/websocket/HvacRealtimeAccessService.java`
- Test: `src/test/java/com/platform/iot/websocket/HvacRealtimeProtocolTest.java`
- Test: `src/test/java/com/platform/iot/websocket/HvacRealtimeAccessServiceTest.java`

**Interfaces:**
- Consumes:
  - `JwtService.parseToken(String)` and its validated claims;
  - `JwtService.getUserId(Claims)` and `JwtService.getRoles(Claims)`;
  - `TokenCacheService.validateActiveToken(Long, String)`;
  - `BuildingScopeService.checkAccess(Long, Collection<String>, String)`.
- Produces:
  - `HvacRealtimeProtocol.ClientMessage decodeClient(String payload)`;
  - `String subscribed(String buildingId, long serverTime)`;
  - `String pong(long serverTime)`;
  - `String error(String code, String message)`;
  - `HvacRealtimeSubscription authenticate(String token, String buildingId)`;
  - `HvacRealtimeSubscription revalidate(HvacRealtimeSubscription current)`.

- [ ] **Step 1: Write failing protocol tests**

Create tests that assert the exact contract:

```java
class HvacRealtimeProtocolTest {
    private final HvacRealtimeProtocol protocol =
            new HvacRealtimeProtocol(new ObjectMapper());

    @Test
    void decodesSubscribeWithoutEchoingToken() {
        var message = protocol.decodeClient("""
                {"type":"SUBSCRIBE","token":"jwt-value","buildingId":"BLD001"}
                """);

        assertThat(message).isEqualTo(
                new HvacRealtimeProtocol.Subscribe("jwt-value", "BLD001"));
        assertThat(protocol.subscribed("BLD001", 100L))
                .contains("\"type\":\"SUBSCRIBED\"")
                .contains("\"buildingId\":\"BLD001\"")
                .doesNotContain("jwt-value");
    }

    @Test
    void rejectsUnknownTypeBlankFieldsAndOversizedPayload() {
        assertThatThrownBy(() -> protocol.decodeClient("{\"type\":\"CONTROL\"}"))
                .isInstanceOf(HvacRealtimeAccessException.class)
                .extracting("errorCode").isEqualTo("BAD_PROTOCOL");
        assertThatThrownBy(() -> protocol.decodeClient(
                "{\"type\":\"SUBSCRIBE\",\"token\":\" \",\"buildingId\":\"BLD001\"}"))
                .isInstanceOf(HvacRealtimeAccessException.class);
        assertThatThrownBy(() -> protocol.decodeClient("x".repeat(16_385)))
                .isInstanceOf(HvacRealtimeAccessException.class);
    }

    @Test
    void decodesPingAndProducesDesensitizedError() {
        assertThat(protocol.decodeClient("{\"type\":\"PING\"}"))
                .isEqualTo(HvacRealtimeProtocol.Ping.INSTANCE);
        assertThat(protocol.error("FORBIDDEN_BUILDING", "无权订阅该建筑"))
                .contains("FORBIDDEN_BUILDING")
                .doesNotContain("token")
                .doesNotContain("stack");
    }
}
```

The production protocol type must be a sealed interface with exactly these client variants:

```java
public sealed interface ClientMessage permits Subscribe, Ping {}
public record Subscribe(String token, String buildingId) implements ClientMessage {}
public enum Ping implements ClientMessage { INSTANCE }
```

- [ ] **Step 2: Write failing access-service tests**

Cover all three result classes without real Redis or MySQL:

```java
@ExtendWith(MockitoExtension.class)
class HvacRealtimeAccessServiceTest {
    @Mock JwtService jwtService;
    @Mock TokenCacheService tokenCacheService;
    @Mock BuildingScopeService buildingScopeService;
    @Mock Jws<Claims> jws;
    @Mock Claims claims;

    @Test
    void authenticatesFormalRoleActiveTokenAndAuthorizedBuilding() {
        stubClaims(7L, List.of("BUILDING_OWNER"), 1_800_000_000_000L);
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);

        var result = service().authenticate("jwt", "BLD001");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.roles()).containsExactly("BUILDING_OWNER");
        assertThat(result.buildingId()).isEqualTo("BLD001");
        verify(buildingScopeService).checkAccess(
                7L, Set.of("BUILDING_OWNER"), "BLD001");
    }

    @Test
    void keepsHttpJwtFallbackWhenRedisIsUnavailable() {
        stubClaims(7L, List.of("BUILDING_OWNER"), 1_800_000_000_000L);
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.CACHE_UNAVAILABLE);

        assertThat(service().authenticate("jwt", "BLD001").userId())
                .isEqualTo(7L);
    }

    @Test
    void mapsRejectedTokenTo4401AndBuildingDenialTo4403() {
        stubClaims(7L, List.of("BUILDING_OWNER"), 1_800_000_000_000L);
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.REJECTED);
        assertFailure("jwt", "BLD001", 4401, "UNAUTHORIZED");

        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);
        doThrow(new BusinessException(403, "无权访问该建筑"))
                .when(buildingScopeService)
                .checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");
        assertFailure("jwt", "BLD001", 4403, "FORBIDDEN_BUILDING");
    }

    @Test
    void mapsUnavailableBuildingAuthorityTo1011WithoutGrantingAccess() {
        stubClaims(7L, List.of("BUILDING_OWNER"), 1_800_000_000_000L);
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);
        doThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .when(buildingScopeService)
                .checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");

        assertFailure("jwt", "BLD001", 1011, "REALTIME_AUTH_UNAVAILABLE");
    }
}
```

Also test invalid JWT, missing user ID, no formal roles, blank building ID, natural expiry and `revalidate` after permission revocation. Test assertions must check stable codes and close codes, never exception detail strings containing token or SQL.

- [ ] **Step 3: Run the two tests and verify they fail**

From the repository root, run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeProtocolTest,HvacRealtimeAccessServiceTest test
```

Expected: FAIL because the four production types do not exist.

- [ ] **Step 4: Implement the exception and subscription records**

Use these exact public contracts:

```java
public final class HvacRealtimeAccessException extends RuntimeException {
    private final String errorCode;
    private final int closeCode;
    private final String publicMessage;

    public HvacRealtimeAccessException(
            String errorCode, int closeCode, String publicMessage) {
        super(publicMessage);
        this.errorCode = errorCode;
        this.closeCode = closeCode;
        this.publicMessage = publicMessage;
    }

    public String errorCode() { return errorCode; }
    public int closeCode() { return closeCode; }
    public String publicMessage() { return publicMessage; }
}
```

```java
public record HvacRealtimeSubscription(
        Long userId,
        Set<String> roles,
        String buildingId,
        String token,
        long expiresAt) {
    public HvacRealtimeSubscription {
        roles = Set.copyOf(roles);
    }
}
```

Class comments must explain that the raw Token exists only for heartbeat revalidation and is never logged or serialized.

- [ ] **Step 5: Implement the protocol codec**

Implement these constants and behaviors:

```java
public static final int MAX_CLIENT_MESSAGE_CHARS = 16_384;
public static final int CLOSE_BAD_PROTOCOL = 4400;
public static final int CLOSE_UNAUTHORIZED = 4401;
public static final int CLOSE_FORBIDDEN = 4403;
public static final int CLOSE_TIMEOUT = 4408;

public ClientMessage decodeClient(String payload) {
    if (payload == null || payload.length() > MAX_CLIENT_MESSAGE_CHARS) {
        throw badProtocol();
    }
    try {
        JsonNode root = objectMapper.readTree(payload);
        String type = requiredText(root, "type");
        if ("PING".equals(type)) return Ping.INSTANCE;
        if ("SUBSCRIBE".equals(type)) {
            return new Subscribe(
                    requiredText(root, "token"),
                    requiredText(root, "buildingId"));
        }
        throw badProtocol();
    } catch (HvacRealtimeAccessException e) {
        throw e;
    } catch (Exception e) {
        throw badProtocol();
    }
}
```

Encode server messages from `Map.of`/mutable maps through `ObjectMapper`; never concatenate user fields into JSON strings. `error` must only accept the stable code and public message already selected by the caller.

- [ ] **Step 6: Implement access authentication and revalidation**

Use one internal method so first subscription and heartbeat have identical semantics:

```java
public HvacRealtimeSubscription authenticate(String token, String buildingId) {
    if (token == null || token.isBlank() ||
            buildingId == null || buildingId.isBlank()) {
        throw unauthorized();
    }
    try {
        Claims claims = jwtService.parseToken(token).getPayload();
        Long userId = jwtService.getUserId(claims);
        Set<String> roles = jwtService.getRoles(claims).stream()
                .map(String::toUpperCase)
                .filter(FormalRole::isFormal)
                .collect(Collectors.toUnmodifiableSet());
        if (userId == null || roles.isEmpty()) throw unauthorized();

        TokenValidationResult tokenState =
                tokenCacheService.validateActiveToken(userId, token);
        if (tokenState == TokenValidationResult.REJECTED) throw unauthorized();

        buildingScopeService.checkAccess(userId, roles, buildingId);
        return new HvacRealtimeSubscription(
                userId, roles, buildingId, token,
                claims.getExpiration().getTime());
    } catch (HvacRealtimeAccessException e) {
        throw e;
    } catch (BusinessException e) {
        if (Integer.valueOf(403).equals(e.getCode())) throw forbidden();
        throw unavailable();
    } catch (DataAccessException e) {
        throw unavailable();
    } catch (JwtException | IllegalArgumentException e) {
        throw unauthorized();
    } catch (RuntimeException e) {
        throw unavailable();
    }
}

public HvacRealtimeSubscription revalidate(
        HvacRealtimeSubscription current) {
    HvacRealtimeSubscription refreshed =
            authenticate(current.token(), current.buildingId());
    if (!refreshed.userId().equals(current.userId())) throw unauthorized();
    return refreshed;
}
```

Do not log caught exception messages in this service. Logging belongs at the endpoint with stable codes and identifiers only.

- [ ] **Step 7: Run targeted tests**

From the repository root, run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeProtocolTest,HvacRealtimeAccessServiceTest test
```

Expected: both classes PASS; no Spring context or external connection starts.

- [ ] **Step 8: Perform production comment review for this task**

Apply `code-comment-quality` at high risk. Check every method in the four new production files. Required comments must explain protocol limits, raw Token lifetime, Redis failure semantics, building authority, error mapping and why unavailable authority fails closed.

- [ ] **Step 9: Commit the protocol and access boundary**

From the repository root, run:

```powershell
git add -- `
  src/main/java/com/platform/iot/websocket/HvacRealtimeProtocol.java `
  src/main/java/com/platform/iot/websocket/HvacRealtimeAccessException.java `
  src/main/java/com/platform/iot/websocket/HvacRealtimeSubscription.java `
  src/main/java/com/platform/iot/websocket/HvacRealtimeAccessService.java `
  src/test/java/com/platform/iot/websocket/HvacRealtimeProtocolTest.java `
  src/test/java/com/platform/iot/websocket/HvacRealtimeAccessServiceTest.java
git diff --cached --check
git commit -m "feat(hvac): secure realtime subscription access"
```

Expected: Hook prints `REPOSITORY_GUARDRAILS_OK` and the commit contains only this task’s files.

---

### Task 2: Route Sessions and Indicator Messages by Building

**Files:**
- Create: `src/main/java/com/platform/config/HvacRealtimeConfig.java`
- Create: `src/main/java/com/platform/iot/websocket/HvacRealtimeSessionRegistry.java`
- Test: `src/test/java/com/platform/iot/websocket/HvacRealtimeSessionRegistryTest.java`
- Modify: `src/main/java/com/platform/iot/websocket/RealtimeMessageGateway.java`
- Modify: `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`
- Modify: `src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java`
- Modify: `src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java`

**Interfaces:**
- Consumes:
  - `HvacRealtimeAccessService.authenticate` and `revalidate` from Task 1;
  - `HvacRealtimeProtocol.CLOSE_TIMEOUT`;
  - Jakarta `Session`, `RemoteEndpoint.Basic`, `CloseReason`.
- Produces:
  - `void open(Session session)`;
  - `HvacRealtimeSubscription subscribe(Session session, String token, String buildingId)`;
  - `HvacRealtimeSubscription ping(Session session)`;
  - `void sendControl(Session session, String message)`;
  - `void sendToBuilding(String buildingId, String message)`;
  - `void close(Session session, int code, String publicReason)`;
  - `void remove(Session session)`;
  - `RealtimeMessageGateway.sendToBuilding(String buildingId, String message)`.

- [ ] **Step 1: Write failing registry routing tests**

Use mocked Jakarta Sessions and a mocked `TaskScheduler`. Create a helper that returns an open Session with a mocked `Basic` remote. Cover:

```java
@Test
void sendsOnlyToAuthenticatedSessionsInTheTargetBuilding() throws Exception {
    Session a = session("A");
    Session b = session("B");
    registry.open(a);
    registry.open(b);
    when(access.authenticate("ta", "BLD001"))
            .thenReturn(subscription(1L, "BLD001", "ta"));
    when(access.authenticate("tb", "BLD002"))
            .thenReturn(subscription(2L, "BLD002", "tb"));
    registry.subscribe(a, "ta", "BLD001");
    registry.subscribe(b, "tb", "BLD002");

    registry.sendToBuilding("BLD001", "message-a");

    verify(a.getBasicRemote()).sendText("message-a");
    verify(b.getBasicRemote(), never()).sendText(anyString());
}
```

Also add tests for:

- pending Session receives no building message;
- resubscribe atomically moves a Session from BLD001 to BLD002;
- `ping` replaces the stored subscription with the revalidated result;
- permission failure during `ping` removes and closes the Session;
- subscription timeout closes with `4408` only if still pending;
- heartbeat timeout closes an authenticated Session when no next `ping` arrives;
- `remove` is idempotent and cancels both futures;
- one send failure removes only that Session and still sends to the next Session;
- two threads calling `sendControl` for the same Session never enter `sendText` concurrently.

- [ ] **Step 2: Update publisher tests to require an explicit building route**

Change the current assertion from `broadcast` to:

```java
verify(gateway).sendToBuilding(eq("BLD001"), message.capture());
```

Change delivery-failure stubbing and verification to the same signature:

```java
doThrow(new IllegalStateException("socket unavailable"))
        .when(gateway).sendToBuilding(eq("BLD001"), anyString());
```

Add a test that an empty or null `buildingId` state is not delivered and does not throw; log only the indicator ID and stable reason, never serialize it into a global route.

- [ ] **Step 3: Run the registry and publisher tests to verify failure**

From the repository root, run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeSessionRegistryTest,IndicatorRealtimePublisherTest test
```

Expected: FAIL because the registry and `sendToBuilding` contract do not exist.

- [ ] **Step 4: Add the managed timeout scheduler**

Implement configuration-only assembly:

```java
@Configuration
public class HvacRealtimeConfig {
    @Bean("hvacRealtimeTaskScheduler")
    public ThreadPoolTaskScheduler hvacRealtimeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("hvac-realtime-timeout-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
```

The comment must state that the scheduler only closes unauthenticated or heartbeat-expired sessions and does not run formula, database or MQTT work.

- [ ] **Step 5: Implement the session registry state model**

Use these constants:

```java
static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);
static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);
```

Use a private state object with exactly one send lock and cancellable timeouts:

```java
private static final class SessionState {
    private final Session session;
    private final Object sendLock = new Object();
    private volatile HvacRealtimeSubscription subscription;
    private volatile ScheduledFuture<?> subscribeTimeout;
    private volatile ScheduledFuture<?> heartbeatTimeout;
}

private final ConcurrentMap<String, SessionState> sessions =
        new ConcurrentHashMap<>();
private final ConcurrentMap<String, ConcurrentMap<String, SessionState>>
        sessionsByBuilding = new ConcurrentHashMap<>();
```

`open` replaces a stale same-ID state only after removing it and schedules the 5-second close. `subscribe` authenticates before mutating building indexes, cancels the pending timeout, removes the previous building mapping under the state lock, stores the new subscription, adds the new mapping, and schedules the 30-second heartbeat deadline.

- [ ] **Step 6: Implement serialized sends and idempotent cleanup**

All control and business frames must share one method:

```java
private void send(SessionState state, String message) throws IOException {
    synchronized (state.sendLock) {
        if (!state.session.isOpen()) throw new IOException("session closed");
        state.session.getBasicRemote().sendText(message);
    }
}
```

`sendToBuilding` iterates only a snapshot of the selected building map. On one send failure, log sessionId/buildingId without message content, remove that Session, then continue. `remove` must use conditional removes so a late close from an old Session cannot remove a newer state with the same ID.

- [ ] **Step 7: Change the gateway and publisher contracts**

Use this interface:

```java
public interface RealtimeMessageGateway {
    void sendToBuilding(String buildingId, String message);
}
```

The adapter becomes constructor-injected:

```java
@Component
@RequiredArgsConstructor
public class WebSocketRealtimeMessageGateway
        implements RealtimeMessageGateway {
    private final HvacRealtimeSessionRegistry registry;

    @Override
    public void sendToBuilding(String buildingId, String message) {
        registry.sendToBuilding(buildingId, message);
    }
}
```

In `IndicatorRealtimePublisher.publish` validate `state.buildingId()` before serialization and call:

```java
gateway.sendToBuilding(state.buildingId(), message);
```

Keep existing best-effort exception handling so formula processing is never rolled back.

- [ ] **Step 8: Run targeted backend tests**

From the repository root, run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeSessionRegistryTest,IndicatorRealtimePublisherTest test
```

Expected: PASS, including cross-building non-delivery and send-failure isolation.

- [ ] **Step 9: Review high-risk concurrency and routing comments**

Use `code-comment-quality` on all six changed production files. Verify comments explain:

- why the scheduler is separate from business schedulers;
- why building ID is a method argument rather than parsed from JSON;
- why one Session uses synchronous serialized sends;
- how resubscribe and late close avoid cross-building removal races;
- why delivery remains best effort after TDengine/Redis success.

- [ ] **Step 10: Commit building routing**

From `web`, run:

```powershell
git add -- `
  src/main/java/com/platform/config/HvacRealtimeConfig.java `
  src/main/java/com/platform/iot/websocket/HvacRealtimeSessionRegistry.java `
  src/main/java/com/platform/iot/websocket/RealtimeMessageGateway.java `
  src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java `
  src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java `
  src/test/java/com/platform/iot/websocket/HvacRealtimeSessionRegistryTest.java `
  src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java
git diff --cached --check
git commit -m "feat(hvac): route realtime indicators by building"
```

Expected: one independently reviewable routing commit.

---

### Task 3: Enforce the WebSocket Protocol Lifecycle at the Endpoint

**Files:**
- Modify: `src/main/java/com/platform/iot/websocket/WebSocketServer.java`
- Modify: `src/main/java/com/platform/security/SecurityConfig.java`
- Modify: `src/main/java/com/platform/config/WebSocketConfig.java`
- Create: `src/test/java/com/platform/iot/websocket/WebSocketServerTest.java`

**Interfaces:**
- Consumes: `HvacRealtimeProtocol` and `HvacRealtimeSessionRegistry` from Tasks 1-2.
- Produces: an anonymous transport handshake whose business stream remains closed until a valid `SUBSCRIBE` frame succeeds.

- [ ] **Step 1: Write failing endpoint lifecycle tests**

Cover these cases with mocked Jakarta `Session` and `RemoteEndpoint.Basic`:

1. `onOpen` only registers pending state and emits no indicator;
2. valid `SUBSCRIBE` calls registry authorization and returns `SUBSCRIBED`;
3. valid `PING` after subscription returns `PONG` and refreshes authorization;
4. malformed frame closes with `4400`;
5. invalid/expired/revoked JWT closes with `4401`;
6. forbidden building closes with `4403`;
7. subscribe or heartbeat timeout closes with `4408` through the registry;
8. unexpected dependency failure closes with `1011`;
9. `onClose` and `onError` both perform idempotent cleanup.

Run:

```powershell
.\mvnw.cmd -Dtest=WebSocketServerTest test
```

Expected: FAIL because the existing static broadcaster has no authenticated lifecycle.

- [ ] **Step 2: Convert the endpoint into a Spring-configured protocol adapter**

Use:

```java
@ServerEndpoint(
        value = "/ws/hvac",
        configurator = SpringConfigurator.class)
@Component
@RequiredArgsConstructor
public class WebSocketServer {
    private final HvacRealtimeProtocol protocol;
    private final HvacRealtimeSessionRegistry registry;
}
```

Do not keep a static Session collection, static Spring context lookup, per-endpoint mutable business state, or global broadcast method.

- [ ] **Step 3: Implement message dispatch and close semantics**

`onMessage` must:

1. reject oversized or malformed input through `HvacRealtimeProtocol`;
2. dispatch `SUBSCRIBE` to `registry.subscribe`;
3. dispatch `PING` to `registry.heartbeat`;
4. send `SUBSCRIBED` or `PONG` through the registry's serialized send path;
5. send a sanitized `ERROR` frame before application close when the Session is still writable;
6. never log raw frames, JWT values or query strings.

Map only the confirmed close codes `4400/4401/4403/4408/1011`. A normal browser close remains `1000` and must not be reported as an application failure.

- [ ] **Step 4: Keep the transport route public but document the real security boundary**

In `SecurityConfig`, keep `/ws/**` permitted for the HTTP upgrade only. Replace the stale anonymous-broadcast comment with a concise Chinese comment that states first-frame JWT validation and building authorization are enforced inside the endpoint flow.

In `WebSocketConfig`, keep `ServerEndpointExporter` under `!test` and document why unit tests do not open a real port.

- [ ] **Step 5: Run endpoint and access tests**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeProtocolTest,HvacRealtimeAccessServiceTest,HvacRealtimeSessionRegistryTest,WebSocketServerTest,IndicatorRealtimePublisherTest test
```

Expected: PASS with no real Redis, MySQL or network access.

- [ ] **Step 6: Review high-risk authentication and error comments**

Use `code-comment-quality` on all changed production files. Confirm comments explain the split between transport handshake and business authorization, heartbeat revalidation, close-code ownership and best-effort delivery. Remove any comment that implies the endpoint is an authoritative data source.

- [ ] **Step 7: Commit the endpoint lifecycle**

Run:

```powershell
git add -- `
  src/main/java/com/platform/iot/websocket/WebSocketServer.java `
  src/main/java/com/platform/security/SecurityConfig.java `
  src/main/java/com/platform/config/WebSocketConfig.java `
  src/test/java/com/platform/iot/websocket/WebSocketServerTest.java
git diff --cached --check
git commit -m "feat(hvac): enforce websocket subscription lifecycle"
```

Expected: backend endpoint behavior is independently reviewable before frontend work starts.

---

### Task 4: Add the Frontend Protocol and Single-Connection Client

**Files:**
- Create: `web/src/auth/session.ts`
- Create: `web/src/auth/session.test.ts`
- Modify: `web/src/utils/request.ts`
- Modify: `web/src/types/hvac.ts`
- Create: `web/src/realtime/hvacRealtimeProtocol.ts`
- Create: `web/src/realtime/hvacRealtimeProtocol.test.ts`
- Create: `web/src/realtime/hvacRealtimeClient.ts`
- Create: `web/src/realtime/hvacRealtimeClient.test.ts`
- Modify: `web/.env.example`

**Interfaces:**
- Consumes: browser JWT and selected `buildingId`.
- Produces: typed lifecycle events without importing Vue or dashboard domain code.

- [ ] **Step 1: Extract a shared browser-session expiry function with tests**

Create:

```ts
export function expireBrowserSession(): void {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  if (window.location.pathname !== '/login') window.location.href = '/login'
}
```

Update both Axios 401 paths in `request.ts` to call this function. Preserve all other HTTP error normalization.

Test that storage is cleared once and an existing login page is not redirected again.

- [ ] **Step 2: Define runtime-validated realtime messages**

Add the four stable indicator codes and server message union without weakening existing HTTP types:

```ts
export type HvacRealtimeServerMessage =
  | { type: 'SUBSCRIBED'; buildingId: string }
  | { type: 'PONG'; timestamp: number }
  | { type: 'HVAC_INDICATOR'; data: HvacRealtimeIndicator }
  | { type: 'ERROR'; code: string; message: string }
```

The parser must reject unknown types, missing building IDs, invalid indicator codes, non-finite minute values and non-array `missingInputs`. It must not coerce invalid payloads into defaults.

- [ ] **Step 3: Build the WebSocket URL without putting credentials in it**

Resolution order:

1. non-empty `VITE_WS_BASE`;
2. `VITE_API_BASE` including its application context path;
3. current browser origin.

Resolve relative bases against the browser origin, convert `http` to `ws` and `https` to `wss`, preserve the existing `/api` application context, append exactly one `/ws/hvac`, and assert in tests that the result is `/api/ws/hvac` rather than `/api/api/ws/hvac`. The resulting URL must contain neither `token` nor `buildingId` query parameters.

Add to `web/.env.example`:

```dotenv
VITE_WS_BASE=ws://localhost:8081/api
```

- [ ] **Step 4: Write failing client tests with an injected socket and timers**

Cover:

- sends exactly one `SUBSCRIBE` after `open`;
- waits at most 5 seconds for `SUBSCRIBED`;
- starts 20-second PING only after subscription;
- requires PONG within 10 seconds;
- forwards valid indicator frames and ignores no frame silently;
- classifies `4400/4401/4403/4408/1011` exactly;
- deliberate `close()` cancels timers and does not request reconnect;
- stale events from a replaced socket are ignored.

Run:

```powershell
npm run test:run -- src/auth/session.test.ts src/realtime/hvacRealtimeProtocol.test.ts src/realtime/hvacRealtimeClient.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: FAIL before the client exists.

- [ ] **Step 5: Implement the framework-independent realtime client**

Expose a small factory/interface, not a singleton:

```ts
export interface HvacRealtimeClient {
  connect(input: { token: string; buildingId: string }): void
  close(): void
}
```

Constructor dependencies must allow a fake `WebSocket`, clock and timer functions in Vitest. The client owns only one socket and its handshake/heartbeat timers; it does not own HTTP polling or reconnect delays.

- [ ] **Step 6: Run frontend protocol tests and type checks**

From `web`, run:

```powershell
npm run test:run -- src/auth/session.test.ts src/realtime/hvacRealtimeProtocol.test.ts src/realtime/hvacRealtimeClient.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Expected: PASS.

- [ ] **Step 7: Review comments and commit the client layer**

Use `code-comment-quality` on `request.ts`, protocol and client files. Explain only non-obvious timer ownership, stale-socket protection and why JWT is sent after upgrade. Return to the repository root, then run:

```powershell
git add -- `
  web/src/auth/session.ts `
  web/src/auth/session.test.ts `
  web/src/utils/request.ts `
  web/src/types/hvac.ts `
  web/src/realtime/hvacRealtimeProtocol.ts `
  web/src/realtime/hvacRealtimeProtocol.test.ts `
  web/src/realtime/hvacRealtimeClient.ts `
  web/src/realtime/hvacRealtimeClient.test.ts `
  web/.env.example
git diff --cached --check
git commit -m "feat(web): add authenticated hvac realtime client"
```

---

### Task 5: Merge Realtime Indicator Deltas Without Corrupting HTTP State

**Files:**
- Modify: `web/src/domain/hvacDashboard.ts`
- Modify: `web/src/domain/hvacDashboard.test.ts`

**Interfaces:**
- Consumes: the latest authoritative HTTP indicator response and one validated WebSocket indicator.
- Produces: a new dashboard indicator state or the original state when the delta is irrelevant/stale.

- [ ] **Step 1: Write failing pure-domain tests**

Cover:

- matching building and known indicator replaces only that card;
- another building is ignored;
- unknown indicator code is ignored defensively;
- an older minute is ignored;
- the same minute may replace status/value/quality/reason fields;
- a newer minute is accepted;
- `null` HTTP state can accept a valid first delta without inventing the other three indicators;
- `MISSING_INPUT` and `CALCULATION_ERROR` retain reason code, message and concrete missing inputs.

From `web`, run:

```powershell
npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: FAIL before the merge helper exists.

- [ ] **Step 2: Implement one immutable merge helper**

Use a signature equivalent to:

```ts
export function mergeRealtimeIndicator(
  current: LatestIndicatorsResponse | null,
  incoming: HvacRealtimeIndicator,
  currentBuildingId: string,
): LatestIndicatorsResponse | null
```

Compare normalized minute values, preserve backend units and audit fields, and never derive missing cards or default values in the browser.

- [ ] **Step 3: Run the domain tests and commit**

From `web`, run:

```powershell
npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Return to the repository root, then run:

```powershell
git add -- web/src/domain/hvacDashboard.ts web/src/domain/hvacDashboard.test.ts
git diff --cached --check
git commit -m "feat(web): merge realtime indicator states"
```

---

### Task 6: Orchestrate Realtime, Reconciliation, Reconnect, and HTTP Fallback

**Files:**
- Modify: `web/src/composables/useHvacDashboard.ts`
- Modify: `web/src/composables/useHvacDashboard.test.ts`

**Interfaces:**
- Consumes: `HvacRealtimeClient`, existing `refreshDashboard`, current token and current building.
- Produces: one authoritative dashboard lifecycle with observable connection state.

- [ ] **Step 1: Add failing state-machine tests using fake timers**

Cover the confirmed lifecycle:

1. initialization performs HTTP before opening realtime;
2. connected but not yet reconciled remains `connecting` and retains 30-second polling;
3. `SUBSCRIBED` triggers immediate HTTP reconciliation;
4. only a successful complete reconciliation stops fallback polling and sets `realtime`;
5. indicator delta updates the card immediately;
6. all deltas in a 500 ms window trigger one trailing HTTP refresh;
7. refresh already in flight results in exactly one trailing refresh, not concurrent calls;
8. disconnect starts immediate HTTP refresh, 30-second non-overlapping polling and reconnect delays `1/2/5/10/30` seconds;
9. a connection stable for 60 seconds resets the backoff index;
10. reconnect success reconciles before polling stops;
11. `4400` stops automatic retry and stays in HTTP fallback;
12. `4401` expires the browser session and stops retry;
13. `4403` marks forbidden without clearing login or retrying that building;
14. `4408/1011` use HTTP fallback and retry;
15. building switch closes the old client, invalidates old callbacks and performs HTTP-first startup for the new building;
16. unmount clears socket, heartbeat-owned callbacks, coalescing timer, retry timer and polling timer.

From `web`, run:

```powershell
npm run test:run -- src/composables/useHvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: FAIL before orchestration exists.

- [ ] **Step 2: Define the UI-facing connection state**

Use one finite union:

```ts
export type HvacRealtimeState =
  | 'connecting'
  | 'realtime'
  | 'reconnecting_with_http'
  | 'http_fallback'
  | 'forbidden'
```

Expose the state and lifecycle methods, but keep raw sockets, timer IDs and retry counters private to the composable.

- [ ] **Step 3: Preserve the existing non-overlapping HTTP refresh contract**

Reuse `refreshPromise` as the single-flight barrier. Add a `refreshRequestedAfterFlight` flag so a WebSocket burst or reconnect does not create parallel snapshot/latest requests and does not lose the final authoritative refresh.

A reconciliation is “complete” only when both current snapshot and latest indicators for the same building succeed. Existing partial-section retention remains valid for display, but partial success must not switch the connection state to `realtime` or stop fallback polling.

- [ ] **Step 4: Implement generation-based lifecycle isolation**

Increment a generation on initialize, building switch and stop. Every client event, delayed reconnect and trailing refresh must capture its generation and return immediately when stale. This prevents a late event from the previous building from modifying the current page.

- [ ] **Step 5: Implement reconnect and fallback ownership**

The composable—not the socket client—owns:

- immediate fallback refresh;
- one 30-second polling interval;
- reconnect schedule `[1000, 2000, 5000, 10000, 30000]`;
- 60-second stability reset;
- retry suppression for `4400/4401/4403`;
- successful-reconciliation transition back to realtime.

Do not run fixed polling alongside a healthy, reconciled WebSocket.

- [ ] **Step 6: Implement 500 ms trailing reconciliation**

Each valid indicator delta merges immediately, then schedules one trailing HTTP reconciliation 500 ms after the newest delta. If a refresh is already running, set the trailing flag and let the single-flight completion start exactly one more refresh.

- [ ] **Step 7: Run composable and domain tests**

From `web`, run:

```powershell
npm run test:run -- src/domain/hvacDashboard.test.ts src/composables/useHvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Expected: PASS with fake timers fully drained and no unhandled promises.

- [ ] **Step 8: Review high-risk frontend comments and commit**

Use `code-comment-quality` on the full changed composable and affected domain call chain. Comments must explain authority, timer ownership, generation isolation and partial-refresh behavior. Return to the repository root, then run:

```powershell
git add -- web/src/composables/useHvacDashboard.ts web/src/composables/useHvacDashboard.test.ts
git diff --cached --check
git commit -m "feat(web): orchestrate realtime fallback dashboard"
```

---

### Task 7: Show the Confirmed Realtime Status on the Existing HVAC Page

**Files:**
- Modify: `web/src/pages/HvacDemoPage.vue`
- Modify: `web/src/pages/HvacDemoPage.contract.test.ts`

**Interfaces:**
- Consumes: `HvacRealtimeState` from the composable.
- Produces: a truthful status label without redesigning the dashboard.

- [ ] **Step 1: Extend the page contract test**

Assert the page:

- calls the unified realtime startup after initial setup;
- calls unified teardown before unmount;
- no longer starts a permanent standalone polling loop;
- renders the five confirmed labels/classes;
- keeps history and formula-detail entry points unchanged.

- [ ] **Step 2: Bind exact state labels and tones**

Use:

| State | Label | Tone |
|---|---|---|
| `connecting` | `实时连接中` | blue |
| `realtime` | `实时连接正常` | green |
| `reconnecting_with_http` | `实时重连中，HTTP 保障` | yellow |
| `http_fallback` | `HTTP 保障中` | yellow |
| `forbidden` | `无该建筑访问权限` | red |

Do not add animations, layout reconstruction, mobile adaptation or a new component library.

- [ ] **Step 3: Replace the old page lifecycle calls**

Keep the existing initial data and modal cleanup behavior. Replace direct permanent `startPolling`/`stopPolling` calls with the composable's unified realtime lifecycle so only one layer owns polling.

- [ ] **Step 4: Run page and integration-focused frontend tests**

From `web`, run:

```powershell
npm run test:run -- src/pages/HvacDemoPage.contract.test.ts src/composables/useHvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Expected: PASS.

- [ ] **Step 5: Review comments and commit the UI state**

Use `code-comment-quality` on changed Vue/TypeScript production code, return to the repository root, then run:

```powershell
git add -- web/src/pages/HvacDemoPage.vue web/src/pages/HvacDemoPage.contract.test.ts
git diff --cached --check
git commit -m "feat(web): show realtime connection status"
```

---

### Task 8: Add a Repeatable Docker Realtime Smoke Test

**Files:**
- Create: `scripts/Test-HvacRealtimeSmoke.ps1`
- Create: `.scripts/test-hvac-realtime.mjs`
- Modify: `scripts/Invoke-CleanHvacSmoke.ps1`
- Create: `src/test/java/com/platform/config/HvacRealtimeSmokeScriptContractTest.java`

**Interfaces:**
- Consumes: the already-started clean Docker environment and application from the existing smoke script.
- Produces: real evidence for authentication, building routing, indicator delivery, reconnect and HTTP reconciliation.

- [ ] **Step 1: Write a failing static script contract test**

Assert both scripts:

- contain no `?token=`, `access_token=` or credential logging;
- contain no recursive delete, broad volume removal or old meter route;
- use `/ws/hvac` and protected HVAC HTTP endpoints;
- keep all credentials in variables/environment and redact failures;
- require Node with global `fetch` and `WebSocket` before running the JS helper.

Run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeSmokeScriptContractTest test
```

Expected: FAIL before the scripts exist.

- [ ] **Step 2: Implement non-destructive test-data preparation**

`Test-HvacRealtimeSmoke.ps1` must:

1. accept API/WS URLs and smoke account names as parameters;
2. verify Docker/application availability without starting or deleting volumes;
3. create or reuse a dedicated second building and restricted test user through idempotent test-environment SQL/API steps;
4. grant the restricted user only the second building;
5. obtain admin and restricted JWT values without printing them;
6. pass short-lived values to the Node helper through process environment variables and clear those variables in `finally`.

Any database preparation must target explicit tables/keys in the current Compose MySQL container and must be idempotent. It must not modify production configuration or seed files.

- [ ] **Step 3: Implement multi-connection assertions in Node**

`.scripts/test-hvac-realtime.mjs` must:

- fail fast unless global `fetch` and `WebSocket` exist;
- subscribe admin to `BLD001` and the restricted user to the second building;
- verify the restricted user receives `4403` when attempting `BLD001`;
- trigger the existing HVAC simulator path and require an `HVAC_INDICATOR` for admin `BLD001`;
- assert the second-building socket does not receive the `BLD001` message during a bounded observation window;
- call the protected latest-indicator HTTP API and compare building, indicator code, minute and status with the WebSocket message;
- close and reconnect the admin socket, require `SUBSCRIBED`, then repeat the HTTP authoritative read;
- print only pass/fail stage names and sanitized errors.

Use bounded timeouts for every network wait so CI/local smoke cannot hang indefinitely.

- [ ] **Step 4: Call the realtime smoke from the existing clean smoke flow**

Add the call only after Compose services, backend health/authentication and existing HVAC clean-state assertions pass. Preserve the existing `-ResetData` confirmation and path guards unchanged.

- [ ] **Step 5: Run contract and real Docker smoke tests**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacRealtimeSmokeScriptContractTest test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-CleanHvacSmoke.ps1 -ResetData
```

Expected markers include the existing clean HVAC smoke success marker plus a new `HVAC_REALTIME_SMOKE_OK`. If Docker or Node runtime is unavailable, record the exact environmental blocker; do not claim the real smoke passed.

- [ ] **Step 6: Commit the smoke automation**

Run:

```powershell
git add -- `
  scripts/Test-HvacRealtimeSmoke.ps1 `
  .scripts/test-hvac-realtime.mjs `
  scripts/Invoke-CleanHvacSmoke.ps1 `
  src/test/java/com/platform/config/HvacRealtimeSmokeScriptContractTest.java
git diff --cached --check
git commit -m "test(hvac): add realtime docker smoke"
```

---

### Task 9: Run the Full Automated Verification Matrix

**Files:**
- Verify only; change production files only to fix failures inside the confirmed scope.

- [ ] **Step 1: Run the complete backend suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS; ordinary tests do not contact real Docker services.

- [ ] **Step 2: Run the complete frontend suite and build gates**

From `web` run:

```powershell
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run lint
npm run check
npm run build
```

Expected: all PASS. Record actual test counts only from this run.

- [ ] **Step 3: Run repository and scope checks**

Run from repository root:

```powershell
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
git status --short
rg -n "(?i)(jwt|token|password).*(=|:)" `
  src/main/java/com/platform/iot/websocket `
  web/src/realtime `
  scripts/Test-HvacRealtimeSmoke.ps1 `
  .scripts/test-hvac-realtime.mjs
```

Manually inspect every match and verify no secret, default password, JWT sample or raw-token log was added. Confirm no database migration, MQTT formula, history, detail-modal or legacy-meter file changed.

- [ ] **Step 4: Fix only verified in-scope failures**

For any failure, write or preserve a regression test, make the smallest correction, rerun the failed command and then rerun the relevant complete suite. Commit fixes explicitly:

```powershell
git add -- <explicit changed paths>
git diff --cached --check
git commit -m "fix(hvac): close realtime verification gaps"
```

Skip this commit when no correction is needed.

---

### Task 10: Validate the Browser Recovery Experience

**Files:**
- Verify: `web/src/pages/HvacDemoPage.vue`
- Verify: runtime only; no unrelated UI changes.

- [ ] **Step 1: Start the validated local stack**

Use the repository's existing Compose/backend/frontend commands documented in `PROJECT_GUIDE.md`. Preserve data unless this validation intentionally follows the already-approved clean smoke reset.

- [ ] **Step 2: Verify the healthy realtime path in the browser**

Using the in-app browser:

1. log in and open `/hvac-demo`;
2. verify the label reaches `实时连接正常`;
3. trigger or wait for a real indicator update;
4. verify the relevant card updates and the subsequent HTTP responses reconcile the complete snapshot/latest state;
5. inspect the WS URL and confirm it has no JWT query parameter.

- [ ] **Step 3: Verify disconnect and recovery**

Temporarily stop only the backend application process, not the data containers. Verify the page shows `实时重连中，HTTP 保障` or `HTTP 保障中`, remains usable, and does not create overlapping HTTP request storms. Restart the backend and verify the page reconnects, performs HTTP reconciliation, then returns to `实时连接正常`.

- [ ] **Step 4: Verify persistent WebSocket failure with healthy HTTP**

Start the frontend once with an intentionally unreachable `VITE_WS_BASE` while keeping `VITE_API_BASE` valid. Verify business data still loads through HTTP, the page remains in `HTTP 保障中`, and retries cap at 30 seconds. Restore the normal environment after the check.

- [ ] **Step 5: Record only observed evidence**

Record browser state transitions, Network-panel observations and any skipped scenario. Do not claim permission revocation or timeout behavior was manually observed when only automated tests covered it.

---

### Task 11: Synchronize Project Documentation With Verified Reality

**Files:**
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`
- Create: `docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md`
- Create: `docs/superpowers/plans/2026-08-10-hvac-realtime-websocket-resilience.md`
- Modify: `docs/superpowers/README.md`

- [ ] **Step 1: Materialize the approved design records in the feature PR**

Read the exact approved files from local ref `feature/hvac-realtime-resilience` with `git show` and add the same content to this fresh task branch using `apply_patch`. Do not merge, rebase or cherry-pick the planning branch, and do not alter confirmed decisions to match implementation shortcuts. These documents are delivered together with working code, not through a standalone documentation PR.

- [ ] **Step 2: Update the stable architecture guide**

Document:

- `/ws/hvac` is four-indicator best-effort delta only;
- HTTP snapshot/latest remains authoritative;
- first-frame JWT/building authorization and heartbeat revalidation;
- building-scoped server routing;
- frontend fallback/reconnect behavior and `VITE_WS_BASE`.

Do not describe 19-point WebSocket snapshots, multi-instance fan-out or HVAC control as implemented.

- [ ] **Step 3: Update current project status from actual test evidence**

Move WebSocket/reconnect/HTTP fallback to completed only if code, full automated suites and real Docker smoke passed. If browser or Docker validation is blocked, mark the corresponding item “代码完成，现场验证未完成” and record the exact remaining risk.

- [ ] **Step 4: Update the MQTT integration document and design index**

Explain that TDengine/Redis success precedes best-effort WebSocket publish, ACK/retry semantics are unchanged, and the client must use HTTP for complete current state. Add the approved spec and implementation plan to `docs/superpowers/README.md`.

- [ ] **Step 5: Run documentation checks and commit**

Run:

```powershell
rg -n "WebSocket|/ws/hvac|HTTP|VITE_WS_BASE|4401|4403" `
  PROJECT_GUIDE.md `
  PROJECT_STATUS.md `
  docs/MQTT-硬件数据对接说明.md `
  docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md `
  docs/superpowers/plans/2026-08-10-hvac-realtime-websocket-resilience.md `
  docs/superpowers/README.md
git diff --check
git add -- `
  PROJECT_GUIDE.md `
  PROJECT_STATUS.md `
  docs/MQTT-硬件数据对接说明.md `
  docs/superpowers/specs/2026-08-10-hvac-realtime-websocket-resilience-design.md `
  docs/superpowers/plans/2026-08-10-hvac-realtime-websocket-resilience.md `
  docs/superpowers/README.md
git diff --cached --check
git commit -m "docs(hvac): document realtime resilience"
```

---

### Task 12: Final Review, Push, and Pull Request Delivery

**Files:**
- Review: all files in `git diff origin/main...HEAD`.

- [ ] **Step 1: Perform the final high-risk comment audit**

Use `code-comment-quality` across every changed production file and affected call chain. Risk level: **high**, because the task changes authentication, building ownership, concurrency, heartbeat/retry state and WebSocket/HTTP cross-layer contracts.

The PR “注释检查” section must list:

- checked files and related call chain;
- authentication/building routing comments;
- session concurrency and timer ownership comments;
- HTTP authority and best-effort WebSocket comments;
- conclusion that no stale anonymous/global-broadcast description remains.

- [ ] **Step 2: Recheck final diff and commits**

Run:

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
```

Expected: only the approved realtime-resilience files; no generated frontend `dist`, logs, local databases, IDE files, credentials or unrelated changes.

- [ ] **Step 3: Push the current Codex task branch**

Run:

```powershell
$taskBranch = git branch --show-current
if ([string]::IsNullOrWhiteSpace($taskBranch) -or $taskBranch -eq 'main') {
  throw '拒绝推送空分支或 main'
}
git push -u origin $taskBranch
```

Expected: push succeeds without force.

- [ ] **Step 4: Prepare the PR with evidence-backed wording**

PR title:

```text
feat(hvac): add resilient building-scoped realtime updates
```

PR body must include:

- scope: secured four-indicator WebSocket delta, building routing, frontend reconciliation/reconnect/HTTP fallback, real smoke automation;
- exclusions: no 19-point WS snapshot, schema change, MQTT/formula/history/control change or multi-instance fan-out;
- architecture: anonymous upgrade plus first-frame JWT authorization; HTTP remains authoritative;
- verification: every actual Maven/Vitest/lint/type/build/Docker/browser command and result;
- unverified items or environmental blockers, if any;
- high-risk comment audit result;
- deployment note: `VITE_WS_BASE`, same-instance WebSocket limitation and rollback by frontend fallback/feature branch revert.

- [ ] **Step 5: Stop after delivery**

Do not merge, delete the branch, remove the Codex worktree or clean Docker data. Those actions require the user to confirm the PR has been merged.

---

## Completion Criteria

This task is complete only when all of the following are true:

- unauthenticated or unauthorized WebSocket sessions receive no business messages;
- authorized sessions receive only their permitted building's four indicator deltas;
- JWT is absent from URL, logs and committed configuration;
- healthy realtime stops permanent polling only after successful HTTP reconciliation;
- disconnect immediately enables HTTP protection and bounded reconnect;
- reconnect performs authoritative HTTP reconciliation before returning to realtime;
- `4400/4401/4403/4408/1011` follow the confirmed state transitions;
- stale building/socket events cannot mutate current page state;
- full backend and frontend verification passes;
- real Docker smoke and browser evidence are reported honestly;
- project guide/status/MQTT documentation match the verified implementation;
- the branch is pushed and a reviewable PR is prepared, but not merged automatically.
