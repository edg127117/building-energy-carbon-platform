# RBAC Code Comments Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为四角色、Spring Security、JWT、动态菜单和建筑授权相关代码补充可维护的中文说明注释，不改变任何运行行为。

**Architecture:** 注释按安全基础、领域模型、服务契约、业务实现和 HTTP 接口五层补充。类注释解释职责和边界，公开方法注释解释调用场景、参数语义和关键返回约定，复杂分支使用行内注释说明安全原因。

**Tech Stack:** Java 21、Spring Boot 3、Spring Security、JWT、MyBatis-Plus、Redis、Javadoc。

## Global Constraints

- 只增加或修正注释，不修改接口路径、方法签名、SQL、权限表达式和业务逻辑。
- 注释使用中文，角色键、缓存约定和 HTTP 路径保留准确英文标识。
- 避免逐行复述代码，只解释职责、用途、特殊返回约定和安全约束。
- 完成后执行完整 Maven 测试和跳过测试的打包验证。

---

### Task 1: 安全基础与缓存注释

**Files:**
- Modify: `src/main/java/com/platform/security/FormalRole.java`
- Modify: `src/main/java/com/platform/security/SecurityUser.java`
- Modify: `src/main/java/com/platform/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/platform/cache/TokenValidationResult.java`
- Modify: `src/main/java/com/platform/cache/BuildingScopeCacheService.java`
- Modify: `src/main/java/com/platform/cache/TokenCacheService.java`
- Modify: `src/main/java/com/platform/iot/controller/ControlFeature.java`

**Interfaces:**
- Consumes: JWT claims、Spring Security `Authentication`、Redis。
- Produces: 正式角色判断、当前用户信息、Token 状态和建筑范围缓存语义说明。

- [ ] **Step 1: 补充类级和公开方法 Javadoc**
- [ ] **Step 2: 说明 `null=全部建筑`、`empty=无建筑` 和 Redis 故障降级约定**
- [ ] **Step 3: 编译确认注释没有影响源代码**

### Task 2: 人员、角色和建筑申请模型注释

**Files:**
- Modify: `src/main/java/com/platform/system/model/BuildingAccessStatus.java`
- Modify: `src/main/java/com/platform/system/model/entity/SysUserBuilding.java`
- Modify: `src/main/java/com/platform/system/model/entity/BuildingAccessRequest.java`
- Modify: `src/main/java/com/platform/system/model/dto/UserAdminDtos.java`
- Modify: `src/main/java/com/platform/system/model/dto/RoleAdminDtos.java`
- Modify: `src/main/java/com/platform/system/model/dto/BuildingAccessDtos.java`

**Interfaces:**
- Consumes: 管理端 JSON 请求和数据库记录。
- Produces: 不暴露密码的人员视图、角色菜单分配请求和申请审核视图。

- [ ] **Step 1: 解释实体和状态流转用途**
- [ ] **Step 2: 为 DTO 分组及每种请求/响应补充用途说明**
- [ ] **Step 3: 核对注释与校验注解一致**

### Task 3: 服务契约与实现注释

**Files:**
- Modify: `src/main/java/com/platform/system/service/SysUserAdminService.java`
- Modify: `src/main/java/com/platform/system/service/SysRoleAdminService.java`
- Modify: `src/main/java/com/platform/system/service/BuildingScopeService.java`
- Modify: `src/main/java/com/platform/system/service/BuildingAccessService.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysUserAdminServiceImpl.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysRoleAdminServiceImpl.java`
- Modify: `src/main/java/com/platform/system/service/impl/BuildingScopeServiceImpl.java`
- Modify: `src/main/java/com/platform/system/service/impl/BuildingAccessServiceImpl.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysMenuServiceImpl.java`

**Interfaces:**
- Consumes: 用户、角色、菜单、建筑授权和访问申请 Mapper。
- Produces: 人员生命周期、角色菜单替换、数据范围校验、审批状态机和动态菜单树。

- [ ] **Step 1: 为服务接口补充职责、参数和返回约定**
- [ ] **Step 2: 为实现类补充事务、安全保护和缓存失效说明**
- [ ] **Step 3: 为复杂算法补充必要行内注释**

### Task 4: HTTP 接口注释与最终验证

**Files:**
- Modify: `src/main/java/com/platform/system/controller/SysUserAdminController.java`
- Modify: `src/main/java/com/platform/system/controller/SysRoleAdminController.java`
- Modify: `src/main/java/com/platform/system/controller/BuildingAccessController.java`
- Modify: `src/main/java/com/platform/system/controller/BuildingAccessAdminController.java`
- Modify: `src/main/java/com/platform/system/controller/AuthController.java`
- Modify: `src/main/java/com/platform/integration/controller/OpenBuildingController.java`

**Interfaces:**
- Consumes: Bearer JWT、请求体和路径参数。
- Produces: 平台管理端、普通用户申请端和第三方只读端点的用途说明。

- [ ] **Step 1: 为控制器和每个端点补充访问角色与用途说明**
- [ ] **Step 2: 运行 `mvn test`，预期全部测试通过**
- [ ] **Step 3: 运行 `mvn -DskipTests package`，预期生成可运行 JAR**
