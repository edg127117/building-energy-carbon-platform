# 四角色 RBAC、动态菜单与建筑授权后端设计

## 1. 目标

本期在不开发前端页面的前提下，补全中央空调经常性调适平台的后端权限闭环：

- 正式启用 `BUILDING_OWNER`、`ENERGY_MANAGER`、`THIRD_PARTY`、`PLATFORM_ADMIN` 四类角色。
- 完善 Spring Security、JWT 登录鉴权、登出和 Token 失效。
- 提供 `PLATFORM_ADMIN` 专用的人员 CRUD、角色分配、角色菜单分配和建筑授权接口。
- 根据当前登录用户动态返回菜单树，并通过 Redis 缓存。
- 对非管理员用户实施建筑级数据隔离。
- 支持用户申请建筑访问权限，由 `PLATFORM_ADMIN` 审批。
- 通过独立测试代码长期验证四角色权限边界。

本设计采用“角色控制接口、数据库控制菜单和建筑范围”的混合方案。它满足本期需求，同时避免提前建设通用权限点引擎。

## 2. 本期范围

### 2.1 包含

- 四类正式角色初始化和平滑迁移。
- 用户注册、登录、登出和当前用户信息。
- 人员分页、详情、创建、修改、逻辑删除、启禁用和密码重置。
- 用户角色分配和用户建筑分配。
- 角色列表、角色菜单查询和角色菜单分配。
- 当前用户动态菜单和菜单缓存失效。
- 建筑范围查询、校验和缓存。
- 建筑访问申请、取消、审批、拒绝和授权撤销。
- 内部 HVAC 接口的角色与建筑范围限制。
- `THIRD_PARTY` 最小只读 Open API。
- 控制接口默认关闭。
- H2、MockMvc 和单元测试。

### 2.2 不包含

- 前端动态菜单和人员管理页面。
- COP 计算引擎和模拟数据。
- SVG 系统拓扑动画和公式弹窗。
- API Key、OAuth2、SSO。
- 通用按钮级权限点引擎。
- 自定义新增第五种角色。
- 联动控制或设备控制。

## 3. 角色和权限边界

### 3.1 四类正式角色

| 角色 | 定位 | 数据范围 |
|---|---|---|
| `BUILDING_OWNER` | 建筑运营方，只读查看能效结果 | 管理员分配的建筑 |
| `ENERGY_MANAGER` | 能效运维和数据配置人员 | 管理员分配的建筑 |
| `THIRD_PARTY` | 第三方系统接口账号 | 管理员分配的建筑 |
| `PLATFORM_ADMIN` | 己方平台管理员 | 全部建筑 |

旧 `ADMIN`、`USER` 数据暂不删除，但业务代码不再依赖。内置 `admin` 账号增加 `PLATFORM_ADMIN` 角色。旧普通用户需要管理员重新分配正式角色。

### 3.2 接口权限矩阵

| 接口类别 | 建筑业主 | 能效管理方 | 对方开发 | 己方管理 |
|---|:---:|:---:|:---:|:---:|
| `/auth/me`、`/auth/logout` | 是 | 是 | 是 | 是 |
| `/menu/current` | 是 | 是 | 是，返回空菜单 | 是 |
| 内部 HVAC 查询 | 授权建筑 | 授权建筑 | 否 | 全部 |
| 建筑权限申请 | 是 | 是 | 是 | 否 |
| `/open-api/**` | 否 | 否 | 授权建筑 | 全部 |
| 人员、角色、菜单管理 | 否 | 否 | 否 | 是 |
| 建筑授权审批 | 否 | 否 | 否 | 是 |
| 建筑、空间、设备、测点修改 | 否 | 否 | 否 | 是 |
| 控制接口 | 否 | 否 | 否 | 否 |

菜单隐藏不是安全边界。所有关键操作必须通过 Spring Security `@PreAuthorize` 在后端执行角色校验。

## 4. 数据模型

### 4.1 沿用表

- `sys_user`
- `sys_role`
- `sys_user_role`
- `sys_menu`
- `sys_role_menu`
- `building`

### 4.2 用户逻辑删除

为 `sys_user` 增加：

```sql
del_flag TINYINT NOT NULL DEFAULT 0
```

`0` 表示正常，`1` 表示已删除。已删除用户不能登录，不出现在默认人员列表中，但记录保留用于追溯。用户名唯一约束继续保留，已删除账号可由管理员通过恢复接口恢复，不自动允许同名重新注册。

### 4.3 用户建筑授权

新增 `sys_user_building`：

```text
id          BIGINT 主键
user_id     BIGINT 用户 ID
building_id VARCHAR(32) 建筑 ID
create_time DATETIME
UNIQUE(user_id, building_id)
```

一个用户可以拥有多个建筑，一个建筑可以授权给多个用户。

### 4.4 建筑访问申请

新增 `sys_building_access_request`：

```text
id              BIGINT 主键
user_id         BIGINT 申请用户
building_id     VARCHAR(32) 申请建筑
reason          VARCHAR(500) 申请原因
status          VARCHAR(16) 申请状态
reviewer_id     BIGINT 审核管理员
review_comment  VARCHAR(500) 审核意见
review_time     DATETIME 审核时间
create_time     DATETIME
update_time     DATETIME
```

状态仅允许：

- `PENDING`
- `APPROVED`
- `REJECTED`
- `CANCELLED`

同一用户和建筑只能存在一条 `PENDING` 申请；被拒绝或取消后可以重新申请。已完成申请保留，不物理删除。

### 4.5 旧设备建筑归属

现有 `iot_device` 增加可空 `building_id`。未绑定建筑的旧设备只允许 `PLATFORM_ADMIN` 查看，系统不通过 `location` 文本推测建筑归属。

## 5. 认证与 Token 生命周期

### 5.1 注册

`POST /auth/register` 执行：

1. 校验用户名唯一、输入格式和密码强度。
2. 使用 BCrypt 保存密码。
3. 创建用户并固定绑定 `BUILDING_OWNER`。
4. 不自动绑定任何建筑。

注册请求不接受角色、建筑、账号状态或管理员字段。

### 5.2 登录

`POST /auth/login` 执行：

1. 校验用户存在、`del_flag=0`、`status=1`。
2. 校验密码。
3. 查询用户启用的正式角色。
4. 将 `userId`、`username`、`roles` 写入 JWT。
5. 将当前 Token 写入 Redis 白名单。

本期采用单账号单有效 Token：

```text
iot:token:whitelist:{userId} -> token
```

同一账号再次登录后，旧 Token 失效。

### 5.3 请求鉴权

请求处理顺序：

1. 读取 Bearer Token。
2. 验证 JWT 签名和过期时间。
3. 检查 Token 黑名单。
4. 检查 Redis 白名单中的 Token 是否一致。
5. 把用户和角色写入 `SecurityContext`。
6. 通过 `@PreAuthorize` 执行接口授权。

Redis 正常时，白名单不存在或 Token 不一致均拒绝访问。Redis 连接故障时降级为仅验证 JWT，并记录告警，避免 Redis 故障造成全员不可用。缓存未命中和 Redis 故障必须在代码中明确区分。

### 5.4 登出和权限变化

新增 `POST /auth/logout`：

- 当前 Token 加入黑名单。
- 删除当前用户白名单 Token。
- 清理用户菜单缓存。

用户角色变化、用户被禁用或逻辑删除后，注销该用户当前 Token，用户必须重新登录。建筑授权不写入 JWT，建筑变化只清理建筑范围缓存，无需重新登录。

## 6. 人员和角色管理

### 6.1 人员接口

以下接口仅允许 `PLATFORM_ADMIN`：

```text
GET    /system/users
GET    /system/users/{id}
POST   /system/users
PUT    /system/users/{id}
DELETE /system/users/{id}
PUT    /system/users/{id}/restore
PUT    /system/users/{id}/status
PUT    /system/users/{id}/password
PUT    /system/users/{id}/roles
PUT    /system/users/{id}/buildings
DELETE /system/users/{id}/buildings/{buildingId}
```

人员列表支持用户名、昵称、手机号、状态和分页筛选。创建用户时未指定角色则使用 `BUILDING_OWNER`。用户 DTO 永远不返回密码。逻辑删除后只能由 `PLATFORM_ADMIN` 显式恢复，恢复不会自动恢复此前撤销的角色或建筑授权。

安全约束：

- 管理员不能删除、禁用自己或取消自己的 `PLATFORM_ADMIN`。
- 系统必须始终保留至少一个正常且未删除的 `PLATFORM_ADMIN`。
- 角色和建筑集合在保存前去重和校验。
- 创建用户、角色分配和建筑分配使用事务。

### 6.2 角色接口

四角色属于冻结数据，不开放角色新增和删除：

```text
GET /system/roles
GET /system/roles/{roleId}
GET /system/roles/{roleId}/menus
PUT /system/roles/{roleId}/menus
```

角色菜单更新在事务内删除旧关联并批量写入新关联，成功后清理受影响用户的菜单缓存。

菜单删除前必须检查是否存在子菜单；存在子菜单时返回 409，不执行级联删除。没有子菜单时，在同一事务中删除角色菜单关联和菜单记录。

## 7. 动态菜单

### 7.1 当前用户菜单

新增 `GET /menu/current`：

1. 从 `SecurityContext` 获取用户 ID。
2. 查询 Redis 用户菜单缓存。
3. 未命中时查询用户所有正式角色的菜单并集。
4. 按菜单 ID 去重并自动补齐父级目录。
5. 构建有序菜单树。
6. 写入 Redis 后返回。

普通用户不能通过参数查询其他角色的菜单。现有 `/menu/tree` 和 `/menu/role/{roleKey}` 改为仅 `PLATFORM_ADMIN` 可用。

### 7.2 默认菜单

- `BUILDING_OWNER`：单机调适中的冷机、冷却塔、水泵和 AHU 能效菜单，不含系统管理。
- `ENERGY_MANAGER`：默认包含建筑业主的能效菜单；后续可由管理员追加数据接入和场景配置菜单。
- `THIRD_PARTY`：不返回内部后台菜单。
- `PLATFORM_ADMIN`：返回全部启用菜单。
- 吸收式 COP 菜单保留但默认隐藏，不分配给本期业务角色。

### 7.3 菜单缓存

用户菜单键：

```text
iot:menu:user:{userId}
```

用户角色变化、角色菜单变化、菜单变更、用户删除和登出时清除相应缓存。批量失效通过角色反查用户 ID 后逐个删除，不使用 Redis `KEYS *`。

## 8. 建筑范围与访问申请

### 8.1 建筑范围服务

新增集中式 `BuildingScopeService`，公开以下语义：

```java
Set<String> getAccessibleBuildingIds(Long userId, Collection<String> roles);
boolean canAccess(Long userId, Collection<String> roles, String buildingId);
void checkAccess(Long userId, Collection<String> roles, String buildingId);
```

`PLATFORM_ADMIN` 访问全部建筑；其他三个角色读取 `sys_user_building`。未授权时列表为空，访问明确建筑时返回 403。

建筑范围缓存：

```text
iot:building-scope:user:{userId}
```

授权、撤销、审批或用户删除后主动失效；Redis 故障时回退 MySQL。

### 8.2 现有资源接入范围校验

建筑、空间、系统分组、HVAC 设备、测点和已绑定建筑的 `iot_device` 均通过 `BuildingScopeService` 限制查询。内部 GET 接口允许 `BUILDING_OWNER`、`ENERGY_MANAGER` 和 `PLATFORM_ADMIN`；内部修改接口只允许 `PLATFORM_ADMIN`。

`THIRD_PARTY` 不访问内部业务接口，只使用 `/open-api/**`。

### 8.3 用户申请接口

允许 `BUILDING_OWNER`、`ENERGY_MANAGER`、`THIRD_PARTY`：

```text
GET  /building-access/available
POST /building-access/requests
GET  /building-access/requests/mine
PUT  /building-access/requests/{id}/cancel
```

后端从 JWT 取得申请用户，不接受客户端提供 `userId`。已授权、建筑无效或存在待审申请时拒绝。

### 8.4 管理员审批接口

仅允许 `PLATFORM_ADMIN`：

```text
GET /system/building-access/requests
PUT /system/building-access/requests/{id}/approve
PUT /system/building-access/requests/{id}/reject
```

审批通过在一个事务中锁定申请、验证状态、幂等写入授权、更新审核信息。事务提交后清理建筑范围缓存。

状态只允许：

```text
PENDING -> APPROVED
PENDING -> REJECTED
PENDING -> CANCELLED
```

## 9. 第三方只读接口

为验证 `THIRD_PARTY` 角色，本轮提供：

```text
GET /open-api/buildings
GET /open-api/buildings/{buildingId}
GET /open-api/buildings/{buildingId}/equipment
GET /open-api/buildings/{buildingId}/datapoints
```

仅 `THIRD_PARTY`、`PLATFORM_ADMIN` 可访问，并强制执行建筑范围。COP 和指标接口在计算引擎完成后再增加，本轮不返回模拟结果。

## 10. 事务、异常和缓存一致性

### 10.1 事务

以下操作使用本地事务：

- 用户创建并绑定角色、建筑。
- 用户角色整体替换。
- 用户建筑整体替换。
- 角色菜单整体替换。
- 用户逻辑删除和权限关系处理。
- 建筑申请审批和授权写入。
- 菜单删除和关联清理。

数据库事务成功后再清理 Redis。Redis 失败只记录日志，不回滚已提交的数据库事务。

### 10.2 HTTP 错误

| HTTP 状态 | 场景 |
|---:|---|
| 400 | 参数错误、非法角色、空角色集合 |
| 401 | 未登录、Token 过期或注销 |
| 403 | 角色无权操作、跨建筑访问 |
| 404 | 用户、角色、菜单、建筑或申请不存在 |
| 409 | 用户名重复、重复申请、重复审批、删除最后管理员 |

响应延续现有 `Result<T>` 和 `BusinessException`，不得返回 SQL、密码、Token 或内部堆栈。现有 `GlobalExceptionHandler` 对 `BusinessException` 固定返回 HTTP 200 的行为必须修正：HTTP 状态与业务错误码保持一致，响应体仍保留 `code`、`msg`、`success` 字段。

## 11. 测试设计

测试代码位于 `src/test/java`，测试数据位于 `src/test/resources`，不打入生产 JAR，也不在后续删除。测试使用 H2 和 MockMvc，不修改正式 MySQL、Redis 或 TDengine。

必须覆盖：

1. 注册默认绑定 `BUILDING_OWNER` 且无建筑权限。
2. 内置 admin 具有 `PLATFORM_ADMIN`。
3. 旧角色不再作为正式权限依据。
4. 未登录返回 401，越权返回 403。
5. 四角色接口行为符合权限矩阵。
6. 人员 CRUD、逻辑删除和密码不泄露。
7. 已删除用户不能登录，恢复后仍需重新分配有效角色才能访问业务接口。
8. 管理员不能删除、禁用或取消自己的管理员角色。
9. 系统不能失去最后一个有效管理员。
10. 多角色菜单合并、去重、补父级。
11. `THIRD_PARTY` 当前菜单为空。
12. 普通用户不能查询其他角色菜单。
13. 角色菜单变化后缓存失效。
14. 未授权建筑列表为空，跨建筑详情返回 403。
15. 建筑申请提交、取消、批准和拒绝状态正确。
16. 重复待审申请返回 409。
17. 审批通过后权限立即生效，撤销后立即失效。
18. `THIRD_PARTY` 只能读取授权建筑的 Open API。
19. 角色变化、禁用和逻辑删除后旧 Token 失效。
20. 控制接口默认不可访问。
21. Redis 不可用时菜单和建筑范围回退 MySQL。

## 12. 验收标准

本设计完成的判定条件：

- 生产初始化脚本和 H2 测试脚本均包含所需结构与四角色数据。
- 所有人员、角色、菜单、建筑授权接口均有明确角色保护。
- 当前用户菜单不接受客户端角色参数。
- 三类非管理员用户无法越权读取未授权建筑。
- `PLATFORM_ADMIN` 可以完整管理人员、角色菜单和建筑授权。
- 建筑权限申请审批形成完整状态闭环。
- 旧 Token 在角色变化、禁用或删除后失效。
- 后端测试通过，测试代码长期保留。
