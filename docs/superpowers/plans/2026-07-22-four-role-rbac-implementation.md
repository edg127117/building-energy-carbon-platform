# Four-Role RBAC and Building Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved backend-only four-role RBAC, JWT lifecycle, personnel administration, dynamic menus, building-scoped access requests, and third-party read APIs.

**Architecture:** Keep role checks explicit with Spring Security `@PreAuthorize`, store menu grants and building grants in MySQL, and use Redis only as a degradable cache. Services own transactions and authorization invariants; controllers accept DTOs and never expose persistence entities containing secrets.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring Security 6, MyBatis-Plus 3.5.5, JJWT 0.12.5, Redis/Lettuce, MySQL 8, H2, JUnit 5, MockMvc.

## Global Constraints

- Implement backend only; do not modify Vue behavior.
- Formal roles are exactly `BUILDING_OWNER`, `ENERGY_MANAGER`, `THIRD_PARTY`, and `PLATFORM_ADMIN`.
- Preserve legacy `ADMIN` and `USER` database rows but do not authorize with them.
- New public registrations receive `BUILDING_OWNER` and no building grant.
- All four formal roles are denied control-command endpoints in this release.
- Personnel deletion is logical deletion and test code remains permanently in `src/test`.
- Redis failures must fall back to database or signed JWT behavior as defined by the design.

---

### Task 1: Database schema, role constants, and persistence models

**Files:**
- Modify: `src/env/init/01-init-tables.sql`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/resources/data-test.sql`
- Modify: `src/main/java/com/platform/system/model/entity/SysUser.java`
- Modify: `src/main/java/com/platform/iot/core/model/entity/IotDevice.java`
- Create: `src/main/java/com/platform/system/model/entity/SysUserBuilding.java`
- Create: `src/main/java/com/platform/system/model/entity/BuildingAccessRequest.java`
- Create: `src/main/java/com/platform/system/model/BuildingAccessStatus.java`
- Create: `src/main/java/com/platform/security/FormalRole.java`
- Create: `src/main/java/com/platform/system/mapper/SysUserBuildingMapper.java`
- Create: `src/main/java/com/platform/system/mapper/BuildingAccessRequestMapper.java`

**Interfaces:**
- Produces: `FormalRole.keys()`, `FormalRole.isFormal(String)`, `SysUserBuilding`, `BuildingAccessRequest`, and mapper CRUD operations used by later services.

- [ ] **Step 1: Expand the H2 schema and seed four roles**

Add `del_flag` to `sys_user`, `data_scope` to `sys_role`, menu tables, `building`, `sys_user_building`, `sys_building_access_request`, and `building_id` on `iot_device`. Seed `admin` with `PLATFORM_ADMIN` and create representative menus/buildings.

- [ ] **Step 2: Expand production initialization idempotently**

Use `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, and `INSERT IGNORE`/guarded inserts. Add role-menu defaults and bind the built-in admin to `PLATFORM_ADMIN` without deleting legacy rows.

- [ ] **Step 3: Add persistence models and formal-role utility**

The role utility must expose immutable formal keys:

```java
public enum FormalRole {
    BUILDING_OWNER, ENERGY_MANAGER, THIRD_PARTY, PLATFORM_ADMIN;

    public static boolean isFormal(String key) {
        return Arrays.stream(values()).anyMatch(role -> role.name().equals(key));
    }
}
```

- [ ] **Step 4: Compile model sources**

Run: `mvn -DskipTests compile`
Expected: compilation succeeds under JDK 21.

### Task 2: Token lifecycle and formal-role authentication

**Files:**
- Modify: `src/main/java/com/platform/cache/TokenCacheService.java`
- Modify: `src/main/java/com/platform/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysUserServiceImpl.java`
- Modify: `src/main/java/com/platform/system/controller/AuthController.java`
- Modify: `src/main/java/com/platform/security/SecurityConfig.java`
- Create: `src/main/java/com/platform/cache/TokenValidationResult.java`
- Test: `src/test/java/com/platform/security/FourRoleAuthenticationTest.java`

**Interfaces:**
- Produces: `TokenCacheService.validateActiveToken(Long, String)`, `TokenCacheService.revokeActiveToken(Long)`, and `POST /auth/logout`.

- [ ] **Step 1: Write failing authentication tests**

Test registration role, admin migration, logout, legacy-role rejection, disabled/deleted login denial, and second-login invalidation.

- [ ] **Step 2: Separate Redis miss from Redis outage**

Implement:

```java
public enum TokenValidationResult { ACTIVE, REJECTED, CACHE_UNAVAILABLE }
```

`ACTIVE` requires exact whitelist equality, `REJECTED` represents absent/mismatched/blacklisted tokens, and `CACHE_UNAVAILABLE` permits signed-JWT fallback with a warning.

- [ ] **Step 3: Enforce formal roles and registration defaults**

Filter role queries through `FormalRole.isFormal`, bind new users to `BUILDING_OWNER`, and bind built-in admin to `PLATFORM_ADMIN` during login migration if necessary.

- [ ] **Step 4: Implement logout and active-token revocation**

Extract the bearer token, blacklist it for remaining JWT lifetime, remove the whitelist entry, and evict the user's menu cache.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=FourRoleAuthenticationTest test`
Expected: all authentication tests pass.

### Task 3: Personnel and fixed-role administration

**Files:**
- Create: `src/main/java/com/platform/system/controller/SysUserAdminController.java`
- Create: `src/main/java/com/platform/system/controller/SysRoleAdminController.java`
- Create: `src/main/java/com/platform/system/service/SysUserAdminService.java`
- Create: `src/main/java/com/platform/system/service/SysRoleAdminService.java`
- Create: `src/main/java/com/platform/system/service/impl/SysUserAdminServiceImpl.java`
- Create: `src/main/java/com/platform/system/service/impl/SysRoleAdminServiceImpl.java`
- Create: `src/main/java/com/platform/system/model/dto/UserAdminDtos.java`
- Create: `src/main/java/com/platform/system/model/dto/RoleAdminDtos.java`
- Modify: `src/main/java/com/platform/system/mapper/SysRoleMapper.java`
- Modify: `src/main/java/com/platform/system/mapper/SysUserMapper.java`
- Modify: `src/main/java/com/platform/system/mapper/SysUserRoleMapper.java`
- Test: `src/test/java/com/platform/system/UserAdministrationTest.java`

**Interfaces:**
- Produces: personnel CRUD/status/password/role/building endpoints and fixed-role list/menu assignment endpoints under `/system`.

- [ ] **Step 1: Write failing personnel-security tests**

Cover pagination, password omission, create/update/delete/restore, self-protection, last-admin protection, password reset, and non-admin 403 behavior.

- [ ] **Step 2: Add DTOs and mapper queries**

Responses expose user metadata, role keys, and building IDs but never `password`. Mapper queries select active formal roles and users affected by role changes.

- [ ] **Step 3: Implement transactional personnel service**

Use `@Transactional(rollbackFor = Exception.class)` for create, role replacement, building replacement, and logical delete. Validate all IDs before deleting old relations. Revoke tokens after committed role/status/delete changes.

- [ ] **Step 4: Implement PLATFORM_ADMIN controllers**

Apply `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` at class level. Validate page size, user fields, role collections, and building collections.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=UserAdministrationTest test`
Expected: all personnel tests pass.

### Task 4: Dynamic current-user menus and cache invalidation

**Files:**
- Modify: `src/main/java/com/platform/system/controller/SysMenuController.java`
- Modify: `src/main/java/com/platform/system/service/SysMenuService.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysMenuServiceImpl.java`
- Modify: `src/main/java/com/platform/system/mapper/SysMenuMapper.java`
- Modify: `src/main/java/com/platform/system/mapper/SysRoleMenuMapper.java`
- Modify: `src/main/java/com/platform/cache/MenuCacheService.java`
- Test: `src/test/java/com/platform/system/DynamicMenuTest.java`

**Interfaces:**
- Produces: `Result<List<SysMenu>> currentMenu(Long userId)` and `GET /menu/current`.

- [ ] **Step 1: Write failing menu tests**

Cover role union, de-duplication, parent completion, sorting, third-party empty menus, cross-role query denial, and invalidation after role-menu changes.

- [ ] **Step 2: Query menus by user rather than caller-provided role**

Add one SQL query joining `sys_user_role`, `sys_role`, `sys_role_menu`, and `sys_menu`, restricted to formal enabled roles and visible enabled menus.

- [ ] **Step 3: Build and cache the tree**

Deserialize cached JSON with `ObjectMapper`; on miss query MySQL, de-duplicate by ID, add ancestors, build children, sort, serialize, and cache.

- [ ] **Step 4: Secure administrative menu endpoints**

`/menu/tree`, `/menu/role/{roleKey}`, and menu mutations require `PLATFORM_ADMIN`. Reject deletion when children exist; otherwise delete role-menu relations and the menu transactionally.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=DynamicMenuTest test`
Expected: all menu tests pass.

### Task 5: Building scope service and existing API enforcement

**Files:**
- Create: `src/main/java/com/platform/cache/BuildingScopeCacheService.java`
- Create: `src/main/java/com/platform/system/service/BuildingScopeService.java`
- Create: `src/main/java/com/platform/system/service/impl/BuildingScopeServiceImpl.java`
- Modify: all controllers under `src/main/java/com/platform/hvac/controller/`
- Modify: corresponding services under `src/main/java/com/platform/hvac/service/` and `service/impl/`
- Modify: `src/main/java/com/platform/iot/controller/IotDeviceController.java`
- Test: `src/test/java/com/platform/security/BuildingScopeTest.java`

**Interfaces:**
- Produces: `getAccessibleBuildingIds`, `canAccess`, and `checkAccess` as defined in the approved design.

- [ ] **Step 1: Write failing data-scope tests**

Test empty grants, granted lists, explicit cross-building 403, platform-admin bypass, and unbound legacy-device visibility.

- [ ] **Step 2: Implement cached scope lookup with MySQL fallback**

Cache the serialized building-ID set under `iot:building-scope:user:{userId}`. Never accept user ID from request parameters.

- [ ] **Step 3: Restrict HVAC reads and writes**

Allow internal reads only to owner, energy manager, and platform admin. Filter lists by accessible IDs and call `checkAccess` for detail/building-path endpoints. Require platform admin for mutations.

- [ ] **Step 4: Restrict legacy devices**

Filter `iot_device` by `building_id`; devices with null building are visible only to platform admin.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=BuildingScopeTest test`
Expected: all scope tests pass.

### Task 6: Building-access request workflow

**Files:**
- Create: `src/main/java/com/platform/system/controller/BuildingAccessController.java`
- Create: `src/main/java/com/platform/system/controller/BuildingAccessAdminController.java`
- Create: `src/main/java/com/platform/system/service/BuildingAccessService.java`
- Create: `src/main/java/com/platform/system/service/impl/BuildingAccessServiceImpl.java`
- Create: `src/main/java/com/platform/system/model/dto/BuildingAccessDtos.java`
- Test: `src/test/java/com/platform/system/BuildingAccessWorkflowTest.java`

**Interfaces:**
- Produces: user request/list/cancel endpoints and admin list/approve/reject endpoints from the approved design.

- [ ] **Step 1: Write failing workflow tests**

Cover available buildings, duplicate pending requests, ownership, cancel, approve, reject, illegal state transitions, authorization creation, and immediate scope-cache eviction.

- [ ] **Step 2: Implement validated user workflow**

Derive `userId` from `JwtUserPrincipal`; reject platform admins, invalid buildings, existing grants, and duplicate pending requests.

- [ ] **Step 3: Implement transactional approval workflow**

Lock the pending request with `SELECT ... FOR UPDATE`, validate user/building activity, insert `sys_user_building` idempotently, update reviewer metadata, commit, then evict scope cache.

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dtest=BuildingAccessWorkflowTest test`
Expected: all request tests pass.

### Task 7: Third-party read APIs, control lockout, and HTTP status correctness

**Files:**
- Create: `src/main/java/com/platform/integration/controller/OpenBuildingController.java`
- Modify: `src/main/java/com/platform/iot/controller/ControlController.java`
- Modify: `src/main/java/com/platform/framework/exception/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/platform/security/ThirdPartyApiTest.java`

**Interfaces:**
- Produces: four `/open-api/buildings` read endpoints and actual HTTP 400/401/403/404/409 responses.

- [ ] **Step 1: Write failing third-party and error-status tests**

Verify internal endpoints reject third party, Open API filters buildings, other roles are rejected, business exceptions use matching HTTP status, and control is disabled for every formal role.

- [ ] **Step 2: Implement minimal read-only Open API**

Use existing building/equipment/datapoint services plus `BuildingScopeService`. Return only authorized records and never add placeholder COP data.

- [ ] **Step 3: Disable control by property and role**

Add `features.control-enabled: false`; require both the feature and an explicit future authorization path. In this release `POST /control/issue` returns 403 for all formal roles.

- [ ] **Step 4: Map business codes to HTTP status**

Return `ResponseEntity` using 400, 401, 403, 404, 409, or 500 while preserving the existing JSON body fields.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=ThirdPartyApiTest test`
Expected: all third-party/security tests pass.

### Task 8: Full regression, documentation, and packaging

**Files:**
- Modify: `docs/superpowers/specs/2026-07-22-four-role-rbac-design.md` only if implementation-discovered facts require clarification
- Modify: `docs/MQTT-硬件数据对接说明.md` only if authentication behavior affects documented endpoints

- [ ] **Step 1: Run all backend tests**

Run: `mvn test`
Expected: all existing and new tests pass under JDK 21.

- [ ] **Step 2: Build the backend artifact**

Run: `mvn package`
Expected: `target/iot-platform-demo-1.0-SNAPSHOT.jar` is produced and contains no test classes.

- [ ] **Step 3: Run the existing frontend checks as a regression guard**

Run from `web`: `npm run check` and `npm run test:run`
Expected: type checking and existing frontend tests pass; no frontend source changes are required.

- [ ] **Step 4: Review security invariants**

Search for remaining `hasRole('ADMIN')`, unrestricted `/menu/role`, plaintext password responses, unscoped building reads, and enabled control endpoints. Expected: none remain in production paths.

- [ ] **Step 5: Commit the completed implementation**

```bash
git add src docs
git commit -m "feat: complete four-role backend authorization"
```

