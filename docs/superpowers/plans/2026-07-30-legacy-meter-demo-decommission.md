# Legacy Meter Demo Decommission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从当前可运行项目中彻底删除旧电表 Demo，同时保持 HVAC 19 测点接入、四类正式角色、四张 TDengine 超级表和 HVAC 指标实时消息链路可用。

**Architecture:** 先用回归测试锁定“旧 HTTP 路由为 404、旧 Schema 不生成、正式角色可用、前端只保留 HVAC 入口”的目标，再依次把 MQTT、WebSocket、TDengine 三个共享点收敛为 HVAC 专用实现。共享点通过定向测试后删除旧后端闭环，最后清理数据库、前端、脚本和当前有效文档，并以静态零残留扫描和完整回归收口。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring Security、MyBatis-Plus、Paho MQTT、MySQL、TDengine、Redis、JUnit 5、Mockito、Vue 3、TypeScript、Pinia、Vue Router、Vitest、Vite

## Global Constraints

- V1 继续保持单体架构，不拆微服务，不进行与旧电表下线无关的模块化重构。
- 旧电表实现直接删除，不移动到 legacy 包，不保留禁用 Bean 或 `legacy-meter.enabled=false` 开关。
- `com.platform.iot` 中的 HVAC 接入、聚合、质量、公式、时序仓储和实时消息边界必须保留。
- MQTT 仍使用现有 19 测点上行主题；服务端可信配置决定来源系统。
- MQTT 合法消息落库后确认，重复或业务无效消息确认丢弃，TDengine 存储失败不确认。
- `/ws/dashboard` 删除，HVAC 指标实时消息只通过 `/ws/hvac` 广播。
- MySQL 和 TDengine 数据源边界保持不变；普通自动化测试不得连接真实外部资源。
- 全新 MySQL 只初始化 `BUILDING_OWNER`、`ENERGY_MANAGER`、`THIRD_PARTY`、`PLATFORM_ADMIN`，内置 `admin` 直接关联 `PLATFORM_ADMIN`。
- 全新 TDengine 只初始化 `st_raw_event`、`st_raw_minute`、`st_indicator_minute`、`st_formula_calc_exception`。
- 不在应用启动时自动执行 `DROP TABLE`；现存测试环境通过显式清理或重建数据库处理。
- 生产代码和重要业务边界注释使用直白中文，说明用途、上下游和失败语义。
- 历史 `docs/superpowers` 不参与静态零残留判定；本设计和实施计划作为审计记录保留。
- 删除前基线：`mvn test` 为 74 个套件、427 个测试通过、0 失败、0 错误、0 跳过；`npm test -- --run` 为 1 个文件、2 个测试通过；`npm run build` 通过，仅有既存 chunk 体积警告。

---

## File Structure

### 保留并修改

- `src/main/java/com/platform/config/MqttConfig.java`：只装配 HVAC MQTT 客户端、上行订阅和手动 ACK。
- `src/main/java/com/platform/config/TdengineConfig.java`：只初始化并验证四张 HVAC 超级表。
- `src/main/java/com/platform/config/TdengineProperties.java`：删除旧电表 `stableName` 属性。
- `src/main/java/com/platform/iot/websocket/WebSocketServer.java`：改为 `/ws/hvac` HVAC 指标广播端点。
- `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`：继续作为公式模块到 WebSocket 的边界。
- `src/main/java/com/platform/system/service/impl/SysUserServiceImpl.java`：删除登录时对旧管理员角色的平滑迁移。
- `src/main/resources/application.yml`：删除旧模拟器、旧稳定表和控制开关配置。
- `src/env/init/01-init-tables.sql`：只初始化用户、四类正式角色和管理员正式角色关联。
- `src/env/init/03-init-hvac-schema.sql`：删除 `iot_device` 兼容升级和管理员平滑迁移措辞。
- `src/test/resources/schema-test.sql`、`src/test/resources/data-test.sql`：删除旧表、旧角色和旧电表数据。
- `src/test/java/com/platform/AuthRbacFlowTest.java`：验证注册/登录正式角色、HVAC 接口鉴权和旧路由 404。
- `src/test/java/com/platform/FourRoleBackendFlowTest.java`：保留四角色流程并验证三个旧路由 404。
- `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`：验证初始化 SQL 只包含 HVAC 超级表。
- `src/test/java/com/platform/DatabaseInitializationTest.java`：验证测试 Schema 不含旧表且只含正式角色。
- `src/test/java/com/platform/config/MqttConfigTest.java`：新增 HVAC 上行订阅与 ACK 语义测试。
- `web/src/router/index.ts`：`/` 转到 `/login`，删除 `/dashboard`、`/device`，登录后临时进入 `/hvac-demo`。
- `web/src/store/auth.ts`：管理员判断改为 `PLATFORM_ADMIN`。
- `web/src/pages/LoginPage.vue`：删除旧电表、控制闭环、普通 USER 和旧 WebSocket 文案。
- `web/src/router/index.test.ts`：新增路由和登录跳转测试，替代设备 Store 测试。
- `docs/设计冻结书-V1.0-19测点.md`：修订 D-007 和当前能力表。
- `docs/MQTT-硬件数据对接说明.md`：改写为 HVAC 19 测点接入说明。
- `docs/HVAC控制能力设计备忘.md`：新增未来控制约束，不包含可执行控制代码。

### 删除

- 后端旧闭环：设计文档第 5.1 节列出的 Controller、Service、Mapper、Entity、消息总线、心跳、控制、旧 MQTT Publisher、旧 TDengine Repository 和设备状态缓存共 23 个 Java 文件。
- 前端旧闭环：`DashboardPage.vue`、`DevicePage.vue`、旧设备/控制 API、设备 Store 与测试、旧 WebSocket 类型/工具、两个旧布局、五个旧页面专用组件及两个空组件。
- 初始化和脚本：`src/env/init/02-init-10000-devices.sql`、`.scripts/simulate-devices.mjs`、`.scripts/stress-test.mjs`、`.scripts/generate-ppt.mjs`。

---

### Task 1: 用回归测试锁定下线后的外部行为

**Files:**
- Modify: `src/test/java/com/platform/AuthRbacFlowTest.java`
- Modify: `src/test/java/com/platform/FourRoleBackendFlowTest.java`
- Modify: `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`
- Modify: `src/test/java/com/platform/DatabaseInitializationTest.java`
- Create: `src/test/java/com/platform/config/MqttConfigTest.java`
- Create: `web/src/router/index.test.ts`

**Interfaces:**
- Consumes: 现有 `/auth/**`、`/hvac/**`、`/system/**`、`HvacMqttMessageHandler.handle(Map<String,Object>, long)` 和 `HvacIngestionResult.shouldAcknowledge()`。
- Produces: 旧 HTTP 路由 404、HVAC MQTT 手动 ACK、四张 HVAC 超级表、正式角色和前端路由的可执行验收契约。

- [ ] **Step 1: 改写认证与四角色流程测试**

`AuthRbacFlowTest` 注册用户后读取 `/auth/me`，断言角色恰好包含 `BUILDING_OWNER`；登录 `admin` 后断言包含 `PLATFORM_ADMIN`；匿名访问正式 HVAC 接口得到 401；已认证管理员访问旧接口得到 404：

```java
assertThat(json(meResult).path("data").path("roles").toString())
        .contains("BUILDING_OWNER");
mockMvc.perform(get("/hvac/query/snapshot"))
        .andExpect(status().isUnauthorized());
for (String path : List.of("/device/list", "/telemetry/history")) {
    mockMvc.perform(get(path).header(auth(), bearer(adminToken)))
            .andExpect(status().isNotFound());
}
mockMvc.perform(post("/control/issue")
        .header(auth(), bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isNotFound());
```

`FourRoleBackendFlowTest` 删除旧控制 403 断言，在原有角色、菜单、建筑申请和开放接口流程末尾复用同一组旧路由 404 断言。

- [ ] **Step 2: 增加数据库和 TDengine 零遗留断言**

`DatabaseInitializationTest` 在加载 `schema-test.sql` 与 `data-test.sql` 后执行：

```java
assertThat(tableNames(jdbcTemplate))
        .doesNotContain("IOT_DEVICE", "IOT_DEVICE_STATUS_LOG", "CONTROL_COMMANDS");
assertThat(jdbcTemplate.queryForList(
        "SELECT role_key FROM sys_role ORDER BY role_key", String.class))
        .containsExactly(
                "BUILDING_OWNER", "ENERGY_MANAGER",
                "PLATFORM_ADMIN", "THIRD_PARTY");
```

`TdengineHvacSchemaTest` 捕获完整初始化 SQL，断言：

```java
assertThat(allSql).contains(
        "st_raw_event", "st_raw_minute",
        "st_indicator_minute", "st_formula_calc_exception");
assertThat(allSql).doesNotContain(
        "st_electric_data", "voltage_a", "current_a", "active_power");
```

- [ ] **Step 3: 新增 MQTT 手动 ACK 契约测试**

用 Mockito 捕获 `IMqttClient.setCallback` 的 `MqttCallbackExtended`：

```java
@Test
void acknowledgesAcceptedAndRejectedHvacMessagesButRetriesStorageFailure() throws Exception {
    IMqttClient client = mock(IMqttClient.class);
    HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
    MqttConfig config = configuredConfig(handler);
    config.initMqttClient(client).run();
    ArgumentCaptor<MqttCallback> callback = ArgumentCaptor.forClass(MqttCallback.class);
    verify(client).setCallback(callback.capture());

    when(handler.handle(anyMap(), anyLong()))
            .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));
    callback.getValue().messageArrived(
            "device/data/up", mqttMessage(41,
                    "{\"deviceId\":\"hvac-gw-1\",\"pointCode\":\"WCR1_TWin\",\"value\":12.3}"));
    verify(client).messageArrivedComplete(41, 1);

    when(handler.handle(anyMap(), anyLong()))
            .thenReturn(HvacIngestionResult.storageFailed("TDengine unavailable"));
    callback.getValue().messageArrived(
            "device/data/up", mqttMessage(42,
                    "{\"deviceId\":\"hvac-gw-1\",\"pointCode\":\"WCR1_TWin\",\"value\":12.3}"));
    verify(client, never()).messageArrivedComplete(42, 1);
}
```

另加缺少 `pointCode` 的旧格式报文测试，断言不调用 `handler` 且调用 `messageArrivedComplete`。

- [ ] **Step 4: 新增前端路由测试**

`web/src/router/index.test.ts` 使用导出的 `routes` 验证：

```ts
expect(routes.find((route) => route.path === '/')?.redirect).toBe('/login')
expect(routes.some((route) => route.path === '/dashboard')).toBe(false)
expect(routes.some((route) => route.path === '/device')).toBe(false)
expect(routes.some((route) => route.path === '/hvac-demo')).toBe(true)
```

同时挂载 Pinia，模拟 `PLATFORM_ADMIN` 用户，断言登录后的默认目标是 `/hvac-demo`。

- [ ] **Step 5: 运行目标测试并确认按预期失败**

Run:

```powershell
mvn -Dtest=AuthRbacFlowTest,FourRoleBackendFlowTest,TdengineHvacSchemaTest,DatabaseInitializationTest,MqttConfigTest test
cd web
npm test -- --run src/router/index.test.ts
```

Expected: 后端因旧路由仍存在、旧 Schema 仍生成或 `MqttConfig` 仍分流电表而失败；前端因旧路由仍存在而失败。

---

### Task 2: 将 MQTT、WebSocket 和 TDengine 收敛为 HVAC 专用共享点

**Files:**
- Modify: `src/main/java/com/platform/config/MqttConfig.java`
- Modify: `src/main/java/com/platform/config/TdengineConfig.java`
- Modify: `src/main/java/com/platform/config/TdengineProperties.java`
- Modify: `src/main/java/com/platform/iot/websocket/WebSocketServer.java`
- Modify: `src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/platform/config/MqttConfigTest.java`
- Test: `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`
- Test: `src/test/java/com/platform/iot/formula/IndicatorRealtimePublisherTest.java`

**Interfaces:**
- Consumes: `mqtt.topics.upstream`、`HvacMqttMessageHandler`、`HvacIngestionResult.shouldAcknowledge()`、`RealtimeMessageGateway.broadcast(String)`。
- Produces: HVAC-only MQTT `CommandLineRunner`、`/ws/hvac` 端点、四张 HVAC TDengine 超级表。

- [ ] **Step 1: 精简 MQTT 连接配置**

删除旧消息总线、`DeviceMessage`、离线系统主题、控制下行主题、Demo 模拟器和普通电表属性分支。订阅主题只来自 `mqtt.topics.upstream`。消息回调按以下顺序处理：

```java
byte[] payloadBytes = message.getPayload();
if (payloadBytes == null || payloadBytes.length == 0) {
    log.warn("MQTT 报文为空，已确认丢弃: topic={}", topic);
    return;
}
if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
    log.warn("MQTT 报文超过 64 KiB，已确认丢弃: topic={}, bytes={}",
            topic, payloadBytes.length);
    return;
}
Map<String, Object> payload = objectMapper.readValue(
        new String(payloadBytes, StandardCharsets.UTF_8), Map.class);
if (!payload.containsKey("pointCode")) {
    log.warn("拒绝旧格式 MQTT 报文：缺少 pointCode，已确认丢弃: topic={}", topic);
    return;
}
HvacIngestionResult result =
        hvacMqttMessageHandler.handle(payload, System.currentTimeMillis());
acknowledge = result.shouldAcknowledge();
```

`finally` 仅在 `acknowledge == true` 时调用 `messageArrivedComplete`。JSON 错误、空包、超大包和缺少 `pointCode` 都是毒消息，确认并丢弃；只有 `STORAGE_FAILED` 不确认。

- [ ] **Step 2: 将 WebSocket 端点改为 HVAC 语义**

`WebSocketServer` 改为：

```java
/**
 * HVAC 指标实时广播端点。
 *
 * <p>该端点只承载公式模块生成的 {@code HVAC_INDICATOR} 消息。
 * JWT 握手和建筑订阅隔离不属于本次下线任务。</p>
 */
@ServerEndpoint("/ws/hvac")
@Component
public class WebSocketServer {
    // 保留线程安全 Session 池、同步发送和失败清理逻辑。
}
```

同步更新日志与 `WebSocketRealtimeMessageGateway` 类注释，删除“大屏”“设备消息”和 `/ws/dashboard` 表述。

- [ ] **Step 3: 删除 TDengine 旧超级表分支**

`TdengineProperties` 删除 `stableName`。`TdengineConfig.doInitialize` 建库后只调用：

```java
initializeHvacSchema(template);
initializeFormulaSchema(template);
List.of(
        properties.getStRawEvent(),
        properties.getStRawMinute(),
        properties.getStIndicatorMinute(),
        properties.getStFormulaCalcException()
).forEach(stable -> verifyInitialization(template, database, stable));
```

删除 `st_electric_data` 建表 SQL、旧表日志和旧表验证。更新类级中文注释，明确 MySQL/TDengine 数据源物理隔离和测试关闭方式。

- [ ] **Step 4: 删除旧运行配置**

从 `application.yml` 删除：

```yaml
mqtt:
  demo-simulator-enabled: false
tdengine:
  stableName: st_electric_data
control:
  control-enabled: false
```

保留 MQTT 连接配置、上行主题、TDengine 初始化开关和四个 HVAC stable 名称。

- [ ] **Step 5: 运行共享点定向测试**

Run:

```powershell
mvn -Dtest=MqttConfigTest,TdengineHvacSchemaTest,IndicatorRealtimePublisherTest,HvacMqttMessageHandlerTest,NineteenPointIngestionAcceptanceTest test
```

Expected: 全部通过，`MqttConfigTest` 明确覆盖确认与重投语义。

- [ ] **Step 6: 提交共享点解耦**

```powershell
git add -- src/main/java/com/platform/config/MqttConfig.java src/main/java/com/platform/config/TdengineConfig.java src/main/java/com/platform/config/TdengineProperties.java src/main/java/com/platform/iot/websocket/WebSocketServer.java src/main/java/com/platform/iot/websocket/WebSocketRealtimeMessageGateway.java src/main/resources/application.yml src/test/java/com/platform/config/MqttConfigTest.java src/test/java/com/platform/config/TdengineHvacSchemaTest.java
git diff --cached --check
git commit -m "refactor(iot): isolate hvac shared runtime boundaries"
```

---

### Task 3: 删除旧后端闭环并迁移安全回归

**Files:**
- Delete: `src/main/java/com/platform/cache/DeviceStatusCacheService.java`
- Delete: `src/main/java/com/platform/iot/algorithm/MpcAlgorithmService.java`
- Delete: `src/main/java/com/platform/iot/controller/ControlController.java`
- Delete: `src/main/java/com/platform/iot/controller/ControlFeature.java`
- Delete: `src/main/java/com/platform/iot/controller/IotDeviceController.java`
- Delete: `src/main/java/com/platform/iot/controller/TelemetryController.java`
- Delete: `src/main/java/com/platform/iot/core/bus/IotMessagePublisher.java`
- Delete: `src/main/java/com/platform/iot/core/handler/CommandAckConsumer.java`
- Delete: `src/main/java/com/platform/iot/core/handler/DeviceMessageConsumer.java`
- Delete: `src/main/java/com/platform/iot/core/heartbeat/DeviceHeartbeatService.java`
- Delete: `src/main/java/com/platform/iot/core/model/DeviceMessage.java`
- Delete: `src/main/java/com/platform/iot/core/model/entity/ControlCommand.java`
- Delete: `src/main/java/com/platform/iot/core/model/entity/IotDevice.java`
- Delete: `src/main/java/com/platform/iot/core/model/entity/IotDeviceStatusLog.java`
- Delete: `src/main/java/com/platform/iot/mapper/ControlCommandMapper.java`
- Delete: `src/main/java/com/platform/iot/mapper/IotDeviceMapper.java`
- Delete: `src/main/java/com/platform/iot/mapper/IotDeviceStatusLogMapper.java`
- Delete: `src/main/java/com/platform/iot/mqtt/MqttPublisher.java`
- Delete: `src/main/java/com/platform/iot/service/IotDeviceService.java`
- Delete: `src/main/java/com/platform/iot/service/impl/ControlCommandServiceImpl.java`
- Delete: `src/main/java/com/platform/iot/service/impl/IotDeviceServiceImpl.java`
- Delete: `src/main/java/com/platform/iot/temporal/TimeSeriesRepository.java`
- Delete: `src/main/java/com/platform/iot/temporal/impl/TDengineRepositoryImpl.java`
- Modify: `src/main/java/com/platform/security/JwtAuthenticationFilter.java`
- Modify: `src/test/java/com/platform/AuthRbacFlowTest.java`
- Modify: `src/test/java/com/platform/FourRoleBackendFlowTest.java`

**Interfaces:**
- Consumes: Task 2 已独立可用的 HVAC MQTT、TDengine 和实时消息边界。
- Produces: 无旧设备、旧遥测、旧控制 Controller/Bean/定时任务的 Spring 应用。

- [ ] **Step 1: 删除设计列出的旧 Java 文件**

逐一删除上方 23 个文件；不得删除 `iot/ingest`、`iot/quality`、`iot/aggregation`、`iot/dataquality`、`iot/formula`、HVAC temporal repository 或 `iot/websocket`。

- [ ] **Step 2: 清理残余注释和导入**

`JwtAuthenticationFilter` 将旧 `/api/device/list` 示例改为正式 HVAC 受保护接口示例。运行：

```powershell
rg -n "IotDevice|DeviceMessage|ControlCommand|MpcAlgorithm|MqttPublisher|TimeSeriesRepository|TDengineRepositoryImpl|DeviceStatusCacheService" src/main src/test
```

Expected: 0 命中。

- [ ] **Step 3: 运行编译和 HTTP 路由回归**

Run:

```powershell
mvn -DskipTests compile
mvn -Dtest=AuthRbacFlowTest,FourRoleBackendFlowTest test
```

Expected: 编译通过；已认证管理员访问 `/device/list`、`/telemetry/history`、`/control/issue` 均为 404。

- [ ] **Step 4: 提交旧后端闭环删除**

仅暂存本任务 Java 文件和两个流程测试，检查暂存列表后提交：

```powershell
git diff --cached --name-only
git diff --cached --check
git commit -m "refactor(iot): remove legacy meter backend loop"
```

---

### Task 4: 清理 MySQL、TDengine 测试数据与正式角色初始化

**Files:**
- Modify: `src/env/init/01-init-tables.sql`
- Delete: `src/env/init/02-init-10000-devices.sql`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/resources/data-test.sql`
- Modify: `src/main/java/com/platform/system/service/impl/SysUserServiceImpl.java`
- Modify: `src/test/java/com/platform/DatabaseInitializationTest.java`

**Interfaces:**
- Consumes: 四类 `FormalRole` 和现有 HVAC Schema。
- Produces: 全新环境无旧表、无旧角色、管理员直接绑定正式角色的初始化脚本。

- [ ] **Step 1: 重写基础角色初始化**

`01-init-tables.sql` 创建 `sys_user`、`sys_role`、`sys_user_role` 后执行：

```sql
INSERT IGNORE INTO `sys_role`
(`role_key`, `role_name`, `data_scope`, `status`) VALUES
('BUILDING_OWNER', '建筑业主', 'BUILDING', 1),
('ENERGY_MANAGER', '能效管理方', 'BUILDING', 1),
('THIRD_PARTY', '对方开发', 'ALL', 1),
('PLATFORM_ADMIN', '己方管理', 'ALL', 1);

INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `sys_user` u
JOIN `sys_role` r ON r.role_key = 'PLATFORM_ADMIN'
WHERE u.username = 'admin';
```

删除三张旧表、`meter-001` 和 `ADMIN`/`USER` 插入。

- [ ] **Step 2: 删除兼容升级和登录平滑迁移**

`03-init-hvac-schema.sql` 删除 `ALTER TABLE iot_device ADD building_id` 和“内置管理员平滑迁移”块；基础脚本已直接绑定管理员，后续脚本只保留幂等的正式 Schema/菜单初始化。

`SysUserServiceImpl.login` 删除：

```java
if ("admin".equalsIgnoreCase(user.getUsername())) {
    SysRole role = sysRoleService.ensureRole(
            FormalRole.PLATFORM_ADMIN.name(), "己方管理");
    bindRoleIfAbsent(user.getId(), role.getId());
}
```

同时删除仅被该逻辑使用的 `bindRoleIfAbsent`。

- [ ] **Step 3: 清理 H2 Schema 和测试数据**

从 `schema-test.sql` 删除三张旧表的 DROP/CREATE；从 `data-test.sql` 删除旧角色和 `meter-001`，保留四类正式角色、管理员正式角色关联和全部 HVAC 测试数据。

- [ ] **Step 4: 运行初始化与认证测试**

Run:

```powershell
mvn -Dtest=DatabaseInitializationTest,AuthRbacFlowTest,FourRoleBackendFlowTest,HvacSeedUnitContractTest,IndustrialIdentityRulesTest test
```

Expected: 全部通过；H2 中旧表不存在，角色查询恰好返回四类正式角色。

- [ ] **Step 5: 提交初始化清理**

```powershell
git diff --cached --name-only
git diff --cached --check
git commit -m "refactor(config): remove legacy meter initialization"
```

---

### Task 5: 删除旧前端并保留可用 HVAC 过渡入口

**Files:**
- Modify: `web/src/router/index.ts`
- Modify: `web/src/store/auth.ts`
- Modify: `web/src/pages/LoginPage.vue`
- Create: `web/src/router/index.test.ts`
- Delete: `web/src/pages/DashboardPage.vue`
- Delete: `web/src/pages/DevicePage.vue`
- Delete: `web/src/pages/HomePage.vue`
- Delete: `web/src/api/device.ts`
- Delete: `web/src/api/control.ts`
- Delete: `web/src/store/device.ts`
- Delete: `web/src/store/device.test.ts`
- Delete: `web/src/types/ws.ts`
- Delete: `web/src/utils/websocket.ts`
- Delete: `web/src/components/ChartPanel.vue`
- Delete: `web/src/components/GlassCard.vue`
- Delete: `web/src/components/MetricFlipper.vue`
- Delete: `web/src/components/StatusBadge.vue`
- Delete: `web/src/components/Empty.vue`
- Delete: `web/src/layouts/ScreenLayout.vue`
- Delete: `web/src/layouts/AdminLayout.vue`

**Interfaces:**
- Consumes: `auth.login`、`auth.register`、`/auth/me` 和暂时保留的 `HvacDemoPage.vue`。
- Produces: `/login`、`/hvac-demo`、`/403` 三个前端路由及正式管理员判断。

- [ ] **Step 1: 精简路由并导出路由表供测试**

```ts
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'login', component: LoginPage, meta: { public: true } },
  {
    path: '/hvac-demo',
    name: 'hvac-demo',
    component: () => import('@/pages/HvacDemoPage.vue'),
  },
  {
    path: '/403',
    name: 'forbidden',
    component: ForbiddenPage,
    meta: { public: true },
  },
]
```

`/hvac-demo` 保持需要登录；路由守卫沿用 JWT 存储恢复和 403 判断。

- [ ] **Step 2: 更新正式角色和登录跳转**

`auth.ts`：

```ts
isAdmin: (s) => (s.userInfo?.roles ?? []).includes('PLATFORM_ADMIN'),
```

`LoginPage.vue` 登录成功后的默认跳转：

```ts
const redirect =
  typeof route.query.redirect === 'string'
    ? route.query.redirect
    : '/hvac-demo'
router.replace(redirect)
```

删除页面上的 WebSocket 地址卡片，以及“电表、设备台账、指令下发、普通 USER、控制闭环”文案；改成 HVAC 采集、数据质量、公式指标和四类正式角色说明。

- [ ] **Step 3: 删除旧页面、业务模块和专用组件**

逐一删除上方列出的 16 个旧前端文件；保留 `LoginPage.vue`、`ForbiddenPage.vue`、`HvacDemoPage.vue`、`api/auth.ts`、`store/auth.ts`、HTTP、主题和全局样式。

- [ ] **Step 4: 运行前端测试、类型检查和构建**

Run:

```powershell
cd web
npm test -- --run
npm run check
npm run build
```

Expected: 路由测试通过；TypeScript 无错误；构建通过。既存大 chunk 警告可记录但不在本任务重构。

- [ ] **Step 5: 提交前端清理**

```powershell
git diff --cached --name-only
git diff --cached --check
git commit -m "refactor(web): remove legacy meter demo"
```

---

### Task 6: 删除旧脚本并更新当前有效文档

**Files:**
- Delete: `.scripts/simulate-devices.mjs`
- Delete: `.scripts/stress-test.mjs`
- Delete: `.scripts/generate-ppt.mjs`
- Modify: `docs/设计冻结书-V1.0-19测点.md`
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Create: `docs/HVAC控制能力设计备忘.md`

**Interfaces:**
- Consumes: 现有 19 测点 MQTT 契约、四个 HVAC 指标和建筑权限设计。
- Produces: 与运行代码一致的当前文档；未来控制仅有设计约束。

- [ ] **Step 1: 删除旧电表专用脚本**

删除三个脚本，保留 `.scripts/simulate-hvac-19-points.mjs`、`generate-ppt-v2.mjs` 和非旧电表资料工具。

- [ ] **Step 2: 修订冻结书**

将 D-007 改为：

```text
V1 只提供 HVAC 数据采集、质量处理、指标计算、查询和实时发布，不提供控制。
未来控制必须使用部署级默认关闭开关、唯一追踪号、审计状态机、ACK/超时、
建筑权限、设备能力、有类型 DTO、安全联锁和协议适配器；旧电表控制实现仅可
从 Git 历史参考，不得恢复到当前运行代码。
```

删除把 `DeviceMessageConsumer`、旧电表 TDengine 缓冲和旧压测脚本列为当前能力的内容。

- [ ] **Step 3: 将 MQTT 对接文档改写为 HVAC 专用**

文档明确：

- 只订阅现有 `mqtt.topics.upstream`。
- 示例载荷包含 `deviceId`、`pointCode`、`value`、`timestamp`。
- 19 个 `pointCode` 与单位沿用冻结书。
- 缺少 `pointCode`、JSON 非法、超大包的拒绝语义。
- 存储成功/重复/业务拒绝确认，TDengine 失败不确认。
- HVAC 指标实时端点为 `/api/ws/hvac`。
- V1 无控制下行主题和控制 API。

- [ ] **Step 4: 新增未来 HVAC 控制备忘**

备忘只记录设计约束、与 `biz_equipment`/建筑权限/协议适配的边界及“必须单独设计验收”，不包含 Controller、Service、SQL、MQTT topic 或可执行占位代码。

- [ ] **Step 5: 提交脚本和文档清理**

```powershell
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(iot): align runtime docs with hvac-only scope"
```

---

### Task 7: 静态零残留、完整回归和干净环境冒烟

**Files:**
- Verify only: `src/main`
- Verify only: `web/src`
- Verify only: `src/env/init`
- Verify only: `src/test`
- Verify only: `.scripts`
- Verify only: `docs/设计冻结书-V1.0-19测点.md`
- Verify only: `docs/MQTT-硬件数据对接说明.md`
- Verify only: `docs/HVAC控制能力设计备忘.md`

**Interfaces:**
- Consumes: Task 2-6 的全部结果。
- Produces: 可推送、可创建 PR 的 HVAC-only 分支及测试证据。

- [ ] **Step 1: 执行静态零残留扫描**

```powershell
rg -n "meter-001|voltage_a|current_a|active_power|iot_device|iot_device_status_log|control_commands|st_electric_data|/device/list|/telemetry/history|/control/issue|/ws/dashboard|demo-simulator-enabled|simulate-devices" src/main web/src src/env/init src/test .scripts docs/设计冻结书-V1.0-19测点.md docs/MQTT-硬件数据对接说明.md docs/HVAC控制能力设计备忘.md
```

Expected: 0 命中。`docs/superpowers`、本设计和本计划是明确历史白名单，不纳入该命令。

- [ ] **Step 2: 执行完整后端回归**

Run:

```powershell
mvn test
```

Expected: BUILD SUCCESS；记录测试套件数、测试数、失败、错误和跳过数量。

- [ ] **Step 3: 执行完整前端回归**

Run:

```powershell
cd web
npm test -- --run
npm run build
```

Expected: 全部测试和构建通过；记录测试文件数、测试数和既存构建警告。

- [ ] **Step 4: 检查本机是否具备干净环境冒烟条件**

```powershell
docker version
docker compose -f src/env/docker-compose.yml config
```

若 Docker 可用，按设计第 10.4 节从空测试数据库启动 MySQL、TDengine、Redis、EMQX 和后端，运行 19 测点模拟器，验证四个指标、HTTP、`/ws/hvac` 及旧表/旧路由不存在。若 Docker 不可用或当前机器无专用测试环境，明确记录“未执行”，不得用单元测试冒充真实链路验收。

- [ ] **Step 5: 检查注释、无关文件和提交边界**

```powershell
git status --short
git diff --check
git diff --name-only origin/main...HEAD
git status --short | Select-String "outputs/|target/|web/dist/|node_modules/"
```

Expected: 没有空白错误、生成物、临时文件或无关模块；修改代码的类级/关键逻辑中文注释与行为一致。

- [ ] **Step 6: 推送任务分支**

```powershell
git push -u origin refactor/legacy-meter-demo-decommission
```

推送后提供：

- Compare 链接：`https://github.com/edg127117/iot-platform-demo/compare/main...refactor/legacy-meter-demo-decommission?expand=1`
- Base：`main`
- Compare：`refactor/legacy-meter-demo-decommission`
- 建议 PR 标题：`refactor(iot): remove legacy meter demo`
- 变更范围、非目标、测试数量、跳过项、未执行冒烟、冲突状态和无关文件检查结果。

当前状态必须表述为“等待用户创建并合并 PR”；用户明确通知“已合并”后，才能同步本地 `main` 并安全删除本地任务分支。
