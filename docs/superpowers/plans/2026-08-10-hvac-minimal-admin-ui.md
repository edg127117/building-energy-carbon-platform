# HVAC Minimal Admin Management UI Implementation Plan

> **执行方式：** 在全新的 Codex 工作树任务中按顺序执行本计划；复选框（`- [ ]`）只用于跟踪步骤，不代表当前已经实现。

**Goal:** 在现有 HVAC V1 中交付用户、固定角色、受控菜单和建筑授权的四个最小管理页面，并补齐菜单管理所需的后端校验、真实路由种子和端到端验收。

**Architecture:** 后端保留现有 Spring Boot 单体和管理 Service，只为菜单增加请求 DTO、完整管理员树与层级校验，并用幂等 SQL 对齐真实运行菜单。前端新增独立管理 API、菜单路由领域层、Pinia 菜单状态、四个页面 Composable 和共享 `ManagementLayout`；数据库菜单只决定已注册真实路由的导航可见性，不动态加载任意 Vue 组件。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring Security、MyBatis-Plus、MySQL 8、Redis、JUnit 5、Mockito、MockMvc、Vue 3、TypeScript 5、Pinia、Vue Router、Ant Design Vue 4、Lucide Vue、Vitest、Docker Compose。

## Global Constraints

- 设计基线是 `docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md`；实现不得改变已确认的四页面范围、固定角色模型或菜单安全边界。
- 编码必须在 Codex 创建的全新工作树中从最新默认 `main` 开始；规划分支 `docs/hvac-admin-management-ui-design` 只读，不设置为 `startingState`，不切换、不合并、不变基、不 cherry-pick。
- 新工作树必须使用仓库允许的 `feature/hvac-minimal-admin-ui` 分支名并通过 `TASK_PREFLIGHT_OK`；不得手工创建额外 Git worktree。
- 开始前完整读取 `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md`、设计、计划、`docs/development/repository-guardrails.md`、`docs/development/code-comments.md` 和 `.agents/skills/iot-change-verification/SKILL.md`。
- 生产代码修改必须使用 `code-comment-quality`；全部仓库变化使用 `iot-change-verification`；Git、提交、推送和 PR 使用 `safe-pr-delivery`。
- UI 实施必须使用 `impeccable`：只继承现有深色工业风，不创建新视觉世界；开始 UI 编码前读取 `reference/craft-floor.md`，完成后执行一次设计检测和有界浏览器检查。
- 四个管理页面仅允许 `PLATFORM_ADMIN`；前端路由和菜单只改善体验，后端 `@PreAuthorize` 才是权限边界。
- 正式角色固定为 `BUILDING_OWNER`、`ENERGY_MANAGER`、`THIRD_PARTY`、`PLATFORM_ADMIN`，不新增、删除或修改角色键。
- 数据库菜单只能映射前端显式注册的真实路径；禁止根据 `component` 字符串动态导入 Vue 文件。
- 未上线菜单保留并设置 `visible=0`，不得删除未来菜单记录。
- 不新增数据库表，不修改 HVAC 采集、公式、历史、详情、WebSocket、控制、建筑档案、空间、设备或测点模块。
- 普通自动化测试不得连接真实 MySQL、Redis、TDengine、EMQX 或现场设备；真实资源只在专用 Docker 冒烟中使用并单独报告。
- API 地址、分页和 DTO 转换不得写进页面；业务操作放入 API/Composable，视觉组件只负责展示与输入。
- 本期不引入 Playwright、任意动态组件加载、移动端专项适配、批量导入导出、复杂拖拽或新 UI 组件库。
- 暂存必须显式列出文件，禁止 `git add .`；禁止绕过 Hook、强推、直接推送 `main` 或自动合并 PR。

---

## File Structure

### Backend files to create

- `src/main/java/com/platform/system/model/dto/MenuAdminDtos.java`
  - 定义菜单新增和更新请求，不暴露数据库时间、树 children 或可注入 ID。
- `src/test/java/com/platform/system/service/SysMenuAdminServiceTest.java`
  - 使用 Mock 验证完整树、父级、自引用、循环、枚举、删除和缓存边界。
- `src/test/java/com/platform/MenuAdminControllerFlowTest.java`
  - 使用 MockMvc 验证管理员/非管理员接口、请求校验和业务冲突。
- `src/env/init/11-migrate-mysql-admin-menu-runtime.sql`
  - 为已有测试数据库幂等对齐真实菜单，保留未来菜单并隐藏未上线入口。
- `src/test/java/com/platform/config/AdminMenuRuntimeSeedContractTest.java`
  - 静态核对基础初始化、增量迁移、角色菜单和真实路径契约。

### Backend files to modify

- `src/main/java/com/platform/system/controller/SysMenuController.java`
  - 增加 `/menu/admin/tree`，新增/更新改用受校验 DTO。
- `src/main/java/com/platform/system/service/SysMenuService.java`
  - 暴露 `adminTree`、DTO 新增/更新契约。
- `src/main/java/com/platform/system/service/impl/SysMenuServiceImpl.java`
  - 实现全量树、DTO 映射、父级和循环校验，保留缓存失效语义。
- `src/env/init/03-init-hvac-schema.sql`
  - 新数据库最终种子包含真实 HVAC/管理入口并隐藏未上线菜单。
- `src/test/resources/data-test.sql`
  - H2 基线对齐真实菜单 ID 和路径。
- `src/test/java/com/platform/FourRoleBackendFlowTest.java`
  - 更新业务角色菜单预期并保留 RBAC/建筑流程证据。
- `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`
  - 明确 11 号脚本是已有库手工迁移，不挂入首次初始化卷。

### Frontend domain and API files to create

- `web/src/types/admin.ts`
  - 用户、角色、菜单、建筑、审批、分页和请求类型。
- `web/src/api/systemAdmin.ts`
  - 所有管理 HTTP 适配函数。
- `web/src/api/systemAdmin.test.ts`
- `web/src/domain/adminNavigation.ts`
  - 真实路径白名单、导航树映射和实现状态判断。
- `web/src/domain/adminNavigation.test.ts`
- `web/src/store/menu.ts`
  - 当前用户菜单加载、并发隔离、失效和重新加载。
- `web/src/store/menu.test.ts`

### Frontend shared management files to create

- `web/src/layouts/ManagementLayout.vue`
  - 管理导航、账号、返回大屏、退出和子路由出口。
- `web/src/layouts/ManagementLayout.test.ts`
- `web/src/components/admin/AdminPageHeader.vue`
  - 统一标题、说明和主操作区域。
- `web/src/styles/admin.css`
  - 管理模块 CSS 变量、布局和可访问状态样式。

### User management files to create

- `web/src/composables/useUserManagement.ts`
- `web/src/composables/useUserManagement.test.ts`
- `web/src/components/admin/UserEditorDrawer.vue`
- `web/src/components/admin/UserRoleAssignmentDrawer.vue`
- `web/src/components/admin/UserBuildingAssignmentDrawer.vue`
- `web/src/pages/admin/UserManagementPage.vue`
- `web/src/pages/admin/UserManagementPage.test.ts`

### Role and menu management files to create

- `web/src/composables/useRoleManagement.ts`
- `web/src/composables/useRoleManagement.test.ts`
- `web/src/components/admin/RoleMenuTree.vue`
- `web/src/pages/admin/RoleManagementPage.vue`
- `web/src/pages/admin/RoleManagementPage.test.ts`
- `web/src/composables/useMenuManagement.ts`
- `web/src/composables/useMenuManagement.test.ts`
- `web/src/components/admin/MenuFormDrawer.vue`
- `web/src/pages/admin/MenuManagementPage.vue`
- `web/src/pages/admin/MenuManagementPage.test.ts`

### Building access files to create

- `web/src/composables/useBuildingAccessManagement.ts`
- `web/src/composables/useBuildingAccessManagement.test.ts`
- `web/src/components/admin/BuildingAccessReviewDrawer.vue`
- `web/src/pages/admin/BuildingAccessManagementPage.vue`
- `web/src/pages/admin/BuildingAccessManagementPage.test.ts`

### Frontend integration files to modify

- `web/src/router/index.ts`
- `web/src/router/index.test.ts`
- `web/src/pages/HvacDemoPage.vue`
- `web/src/pages/HvacDemoPage.contract.test.ts`
- `web/src/style.css`

### Smoke and documentation files

- Create `scripts/Test-HvacAdminSmoke.ps1`
- Create `src/test/java/com/platform/config/HvacAdminSmokeScriptContractTest.java`
- Modify `scripts/Invoke-CleanHvacSmoke.ps1`
- Modify `PROJECT_GUIDE.md`
- Modify `PROJECT_STATUS.md`
- Modify `docs/superpowers/README.md`
- Materialize approved spec and this plan from the read-only planning branch into the feature PR only after implementation verification.

---

### Task 0: Create the Fresh Codex Worktree Task and Pass Preflight

**Files:**
- Read: `AGENTS.md`
- Read: `PROJECT_GUIDE.md`
- Read: `PROJECT_STATUS.md`
- Read from planning ref: `docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md`
- Read from planning ref: `docs/superpowers/plans/2026-08-10-hvac-minimal-admin-ui.md`
- Read: `docs/development/repository-guardrails.md`
- Read: `docs/development/code-comments.md`
- Read: `.agents/skills/iot-change-verification/SKILL.md`

**Interfaces:**
- Consumes: the latest project default `main` and read-only planning ref `docs/hvac-admin-management-ui-design`.
- Produces: a clean Codex worktree on `feature/hvac-minimal-admin-ui` with an empty initial diff.

- [ ] **Step 1: Inspect the new worktree before changing files**

Run:

```powershell
git status --short --branch
git branch --show-current
git log -5 --oneline --decorate
git worktree list --porcelain
```

Expected: clean worktree. The current branch may initially be detached or use a Codex-generated name, but no feature changes may exist.

- [ ] **Step 2: Attach the allowed implementation branch**

If detached, run:

```powershell
git switch -c feature/hvac-minimal-admin-ui origin/main
```

If Codex created an invalid empty branch name, run before any commit:

```powershell
git branch -m feature/hvac-minimal-admin-ui
```

Expected: `git branch --show-current` prints `feature/hvac-minimal-admin-ui`. If that branch already exists in another worktree, stop and report the exact worktree instead of inventing another branch.

- [ ] **Step 3: Read the approved design and plan without changing ancestry**

Run:

```powershell
git show docs/hvac-admin-management-ui-design:docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md
git show docs/hvac-admin-management-ui-design:docs/superpowers/plans/2026-08-10-hvac-minimal-admin-ui.md
```

Expected: both documents are readable. Do not merge, rebase or cherry-pick the planning branch.

- [ ] **Step 4: Verify the baseline and run preflight**

Run:

```powershell
git merge-base --is-ancestor origin/main HEAD
if ($LASTEXITCODE -ne 0) { throw '实现分支不包含最新 origin/main' }
git diff --name-only origin/main...HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

Expected: initial diff is empty and output contains `TASK_PREFLIGHT_OK`.

- [ ] **Step 5: Load required implementation skills**

Read `code-comment-quality`, `iot-change-verification`, `safe-pr-delivery` and `impeccable`. Run once:

```powershell
node .agents/skills/impeccable/scripts/context.mjs --target web/src/pages/HvacDemoPage.vue
```

Because this is an extension of the existing application, inherit its visual authority. Immediately before the first UI production edit, read `.agents/skills/impeccable/reference/craft-floor.md`; do not create a new visual world or unrelated `PRODUCT.md`/`DESIGN.md`.

---

### Task 1: Harden the Menu Administration Contract

**Files:**
- Create: `src/main/java/com/platform/system/model/dto/MenuAdminDtos.java`
- Modify: `src/main/java/com/platform/system/controller/SysMenuController.java`
- Modify: `src/main/java/com/platform/system/service/SysMenuService.java`
- Modify: `src/main/java/com/platform/system/service/impl/SysMenuServiceImpl.java`
- Create: `src/test/java/com/platform/system/service/SysMenuAdminServiceTest.java`
- Create: `src/test/java/com/platform/MenuAdminControllerFlowTest.java`

**Interfaces:**
- Produces: `Result<List<SysMenu>> adminTree()`.
- Produces: `Result<SysMenu> add(MenuAdminDtos.CreateRequest request)`.
- Produces: `Result<SysMenu> update(MenuAdminDtos.UpdateRequest request)`.
- Keeps: `/menu/current`, `/menu/tree`, `/menu/role/{roleKey}` and `/menu/delete/{id}` behavior.

- [ ] **Step 1: Define failing DTO and validation tests**

Create service tests covering:

```java
@Test void adminTreeIncludesDisabledAndHiddenMenus() {}
@Test void addRejectsMissingParent() {}
@Test void updateRejectsSelfParent() {}
@Test void updateRejectsAncestorCycle() {}
@Test void rejectsUnsupportedMenuType() {}
@Test void rejectsInvalidVisibleOrStatus() {}
@Test void updateEvictsAllActiveUserMenuCaches() {}
```

Mock `SysMenuMapper`, `SysRoleMenuMapper`, `SysUserMapper` and `MenuCacheService`; do not start Spring or Redis.

- [ ] **Step 2: Define failing controller flow tests**

Use `@SpringBootTest`, MockMvc and the `test` profile. Assert:

```java
mockMvc.perform(get("/menu/admin/tree").header(auth(), bearer(ownerToken)))
        .andExpect(status().isForbidden());

mockMvc.perform(get("/menu/admin/tree").header(auth(), bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());

mockMvc.perform(post("/menu/add")
        .header(auth(), bearer(adminToken))
        .contentType(APPLICATION_JSON)
        .content("""
                {"parentId":0,"menuName":"非法菜单","menuType":"X",
                 "visible":1,"status":1,"sortOrder":1}
                """))
        .andExpect(status().isBadRequest());
```

Also cover update cycle conflict and a valid hidden child create/update/delete round trip.

- [ ] **Step 3: Run tests to verify failure**

From the repository root, run:

```powershell
.\mvnw.cmd -Dtest=SysMenuAdminServiceTest,MenuAdminControllerFlowTest test
```

Expected: FAIL because the DTO and `/menu/admin/tree` do not exist.

- [ ] **Step 4: Implement request DTOs**

Create exact records:

```java
public final class MenuAdminDtos {
    private MenuAdminDtos() {}

    public record CreateRequest(
            Long parentId,
            @NotBlank @Size(max = 50) String menuName,
            @NotBlank @Pattern(regexp = "[MCF]") String menuType,
            @Size(max = 200) String path,
            @Size(max = 255) String component,
            @Size(max = 100) String perms,
            @Size(max = 100) String icon,
            @Min(0) @Max(1) Integer visible,
            @Min(0) @Max(1) Integer status,
            Integer sortOrder) {}

    public record UpdateRequest(
            @NotNull Long id,
            Long parentId,
            @NotBlank @Size(max = 50) String menuName,
            @NotBlank @Pattern(regexp = "[MCF]") String menuType,
            @Size(max = 200) String path,
            @Size(max = 255) String component,
            @Size(max = 100) String perms,
            @Size(max = 100) String icon,
            @Min(0) @Max(1) Integer visible,
            @Min(0) @Max(1) Integer status,
            Integer sortOrder) {}
}
```

Default `parentId=0`, `visible=1`, `status=1`, `sortOrder=0` inside the Service, not the Controller.

- [ ] **Step 5: Implement full admin tree and structural validation**

Add:

```java
@Override
public Result<List<SysMenu>> adminTree() {
    return Result.success(buildTree(list(
            new LambdaQueryWrapper<SysMenu>()
                    .orderByAsc(SysMenu::getSortOrder)
                    .orderByAsc(SysMenu::getId))));
}
```

Before add/update:

1. normalize string fields and defaults;
2. require nonzero parent to exist;
3. reject `id.equals(parentId)`;
4. for update, walk the proposed parent chain until `0`, using a visited set; reject when the updated ID or another repeated ID is reached;
5. map only DTO fields into a new or existing entity;
6. save/update and evict all active menu caches.

Do not accept request `createTime`, `updateTime` or `children`.

- [ ] **Step 6: Add the admin endpoint without changing existing tree semantics**

Add:

```java
@GetMapping("/admin/tree")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public Result<List<SysMenu>> adminTree() {
    return menuService.adminTree();
}
```

Change `/menu/add` and `/menu/update` to accept `@Valid` DTOs. Preserve `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` on all writes.

- [ ] **Step 7: Run targeted backend tests**

```powershell
.\mvnw.cmd -Dtest=SysMenuAdminServiceTest,MenuAdminControllerFlowTest,FourRoleBackendFlowTest test
```

Expected: PASS without external connections.

- [ ] **Step 8: Review high-risk comments and commit**

Use `code-comment-quality` at high risk for menu authority, cycle validation, cache invalidation and front-end-only navigation semantics. Then:

```powershell
git add -- `
  src/main/java/com/platform/system/model/dto/MenuAdminDtos.java `
  src/main/java/com/platform/system/controller/SysMenuController.java `
  src/main/java/com/platform/system/service/SysMenuService.java `
  src/main/java/com/platform/system/service/impl/SysMenuServiceImpl.java `
  src/test/java/com/platform/system/service/SysMenuAdminServiceTest.java `
  src/test/java/com/platform/MenuAdminControllerFlowTest.java
git diff --cached --check
git commit -m "feat(system): harden menu administration"
```

---

### Task 2: Align Runtime Menu Seeds With Implemented Routes

**Files:**
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Create: `src/env/init/11-migrate-mysql-admin-menu-runtime.sql`
- Modify: `src/test/resources/data-test.sql`
- Modify: `src/test/java/com/platform/FourRoleBackendFlowTest.java`
- Modify: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`
- Create: `src/test/java/com/platform/config/AdminMenuRuntimeSeedContractTest.java`

**Interfaces:**
- Produces stable active menu IDs: `100,101,200,210,211,212,220,223,240,241`.
- Produces real leaf paths: `/hvac-demo`, `/system/users`, `/system/roles`, `/system/menus`, `/system/building-access`.
- Keeps all other existing menu rows and marks them `visible=0`.

- [ ] **Step 1: Write failing seed contract tests**

Read both SQL files as UTF-8 and assert:

```java
assertThat(baseSql).contains("(101", "'/hvac-demo'", "(223", "'/system/building-access'");
assertThat(migrationSql).contains("ON DUPLICATE KEY UPDATE");
assertThat(migrationSql).doesNotContain("DELETE FROM `sys_menu`");
assertThat(migrationSql).contains("visible=0");
assertThat(migrationSql).contains("BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN");
```

Assert Compose does not mount script 11 automatically because existing volumes do not rerun MySQL init migrations.

- [ ] **Step 2: Run seed tests to verify failure**

```powershell
.\mvnw.cmd -Dtest=AdminMenuRuntimeSeedContractTest,DockerComposeConfigurationTest,FourRoleBackendFlowTest test
```

Expected: FAIL because runtime menu rows are still stale and script 11 is absent.

- [ ] **Step 3: Update the clean-database base seed**

In `03-init-hvac-schema.sql`:

- add `101 /hvac-demo` under `100`;
- rename/repoint IDs `100,210,211,212,220,240,241` to the confirmed catalog;
- add `223 /system/building-access` under `220`;
- set every unimplemented existing item to `visible=0`;
- keep `status` unchanged;
- grant `100,101` to `BUILDING_OWNER` and `ENERGY_MANAGER`;
- grant all active management items to `PLATFORM_ADMIN`;
- grant no internal menu to `THIRD_PARTY`.

- [ ] **Step 4: Add the idempotent existing-database migration**

Script 11 must use explicit `INSERT ... ON DUPLICATE KEY UPDATE`, explicit `UPDATE ... WHERE id IN (...)`, and `INSERT IGNORE` role-menu links. It must not delete menu rows, role rows, user links or databases.

Begin with:

```sql
-- 已有 MySQL 测试库的 HVAC/管理菜单对齐；手工执行，Docker 已有卷不会自动重跑。
USE `iot_platform`;
```

End with deterministic role-menu state for the confirmed active routes while preserving unrelated future rows.

- [ ] **Step 5: Align H2 seed and flow expectations**

Update `data-test.sql` with IDs `101,212,220,223,240,241`. Change the owner menu expectation in `FourRoleBackendFlowTest` from the unimplemented `132` leaf to real `101`, and assert non-admin still receives no management IDs.

- [ ] **Step 6: Run targeted seed and RBAC tests**

```powershell
.\mvnw.cmd -Dtest=AdminMenuRuntimeSeedContractTest,DockerComposeConfigurationTest,FourRoleBackendFlowTest,MenuAdminControllerFlowTest test
```

Expected: PASS.

- [ ] **Step 7: Review…4008 tokens truncated…
- Create: `web/src/pages/admin/MenuManagementPage.test.ts`

**Interfaces:**
- Reads `/menu/admin/tree`; writes through validated menu add/update/delete endpoints.
- Uses `isImplementedMenuPath()` only for an informational implementation badge.

- [ ] **Step 1: Write failing menu-management tests**

Cover:

- disabled and hidden nodes remain present in the administration tree;
- expand/collapse state survives a successful refresh when IDs still exist;
- add, edit and leaf delete are explicit operations with duplicate-submit protection;
- parent candidates exclude the node itself and all descendants;
- type, visibility and status use constrained values;
- backend validation errors keep the drawer open;
- successful mutations reload both the administration tree and current navigation;
- implemented badges require an exact registry match and never make a route executable.

- [ ] **Step 2: Implement the menu composable**

Own `tree`, `lastSuccessfulTree`, `loading`, `error`, `generation` and action pending state. Derive flat parent options without mutating backend nodes. Keep the backend as the final authority for parent existence, cycles, leaf deletion, enum values and conflicts.

- [ ] **Step 3: Implement the form and tree page**

The form exposes only the accepted fields: parent, name, type, path, component, permission, icon, order, visible and status. Explain `M/C/F` using Chinese labels. Display “已接入页面” or “未接入页面”; do not expose unimplemented paths as clickable links.

- [ ] **Step 4: Run menu-management tests**

From `web`:

```powershell
npm run test:run -- src/composables/useMenuManagement.test.ts src/pages/admin/MenuManagementPage.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Expected: PASS.

- [ ] **Step 5: Review comments and commit**

Use `code-comment-quality` at high risk for tree cycles, dynamic-navigation boundaries and cache invalidation. From repository root:

```powershell
git add -- `
  web/src/composables/useMenuManagement.ts `
  web/src/composables/useMenuManagement.test.ts `
  web/src/components/admin/MenuFormDrawer.vue `
  web/src/pages/admin/MenuManagementPage.vue `
  web/src/pages/admin/MenuManagementPage.test.ts
git diff --cached --check
git commit -m "feat(web): add menu tree management"
```

---

### Task 9: Implement Building Authorization Operations

**Files:**
- Create: `web/src/composables/useBuildingAccessManagement.ts`
- Create: `web/src/composables/useBuildingAccessManagement.test.ts`
- Create: `web/src/components/admin/BuildingAccessReviewDrawer.vue`
- Create: `web/src/pages/admin/BuildingAccessManagementPage.vue`
- Create: `web/src/pages/admin/BuildingAccessManagementPage.test.ts`

**Interfaces:**
- Direct assignment reuses `UserBuildingAssignmentDrawer.vue`.
- Application review uses the existing list/approve/reject APIs; no building CRUD is introduced.

- [ ] **Step 1: Write failing building-access tests**

Cover:

- pending/all/status filters isolate stale responses and refresh the non-paginated backend list;
- direct assignment loads the selected user's current building IDs and replaces them after confirmation;
- approval and rejection are single-submit operations;
- approval and rejection accept the backend-defined optional review comment;
- already processed applications are read-only;
- successful operations refresh the request list and affected user detail;
- failed operations retain the last successful list and display the backend reason;
- no create, edit or delete building control exists.

- [ ] **Step 2: Implement the composable and review drawer**

Keep direct assignment and request review as two explicit command groups. Do not merge the two authorization paths into an ambiguous generic form. Preserve backend ownership checks and request state as the final authority.

- [ ] **Step 3: Implement the page**

Use two clearly separated panels: “直接授权” and “申请审核”. The page may share user/building selectors, but it must state whether an action immediately replaces authorization or approves an existing request.

- [ ] **Step 4: Run building-access tests**

From `web`:

```powershell
npm run test:run -- src/composables/useBuildingAccessManagement.test.ts src/pages/admin/BuildingAccessManagementPage.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
```

Expected: PASS.

- [ ] **Step 5: Review comments and commit**

Use `code-comment-quality` at high risk for authorization replacement and request-state transitions. From repository root:

```powershell
git add -- `
  web/src/composables/useBuildingAccessManagement.ts `
  web/src/composables/useBuildingAccessManagement.test.ts `
  web/src/components/admin/BuildingAccessReviewDrawer.vue `
  web/src/pages/admin/BuildingAccessManagementPage.vue `
  web/src/pages/admin/BuildingAccessManagementPage.test.ts
git diff --cached --check
git commit -m "feat(web): add building access management"
```

---

### Task 10: Integrate Routes, Guards and the Dashboard Entry

**Files:**
- Modify: `web/src/router/index.ts`
- Modify: `web/src/router/index.test.ts`
- Modify: `web/src/pages/HvacDemoPage.vue`
- Modify: `web/src/pages/HvacDemoPage.contract.test.ts`
- Modify: `web/src/style.css`

**Interfaces:**
- Adds one nested `/system` route using `ManagementLayout` and four allowlisted children.
- Keeps the existing authentication and `meta.admin` backend-role guard.

- [ ] **Step 1: Write failing router and dashboard-entry tests**

Cover:

- `/system/users`, `/system/roles`, `/system/menus` and `/system/building-access` resolve to the expected lazy-loaded page names;
- an unauthenticated visit redirects to `/login` with the intended target;
- a non-administrator receives `/403` even if a menu payload is forged;
- an administrator can refresh each deep link directly;
- the dashboard management entry is visible only when the user is `PLATFORM_ADMIN` and current navigation contains an implemented management path;
- logout clears current menu state;
- old unimplemented routes still resolve to 404 rather than an arbitrary backend component.

- [ ] **Step 2: Add the nested route registry**

Use explicit lazy imports only:

```ts
{
  path: '/system',
  component: () => import('../layouts/ManagementLayout.vue'),
  meta: { requiresAuth: true, admin: true },
  children: [
    { path: 'users', name: 'system-users', component: () => import('../pages/admin/UserManagementPage.vue') },
    { path: 'roles', name: 'system-roles', component: () => import('../pages/admin/RoleManagementPage.vue') },
    { path: 'menus', name: 'system-menus', component: () => import('../pages/admin/MenuManagementPage.vue') },
    { path: 'building-access', name: 'system-building-access', component: () => import('../pages/admin/BuildingAccessManagementPage.vue') },
  ],
}
```

Apply the existing route-meta typing and guard conventions. Do not import Vue files from backend `component` strings.

- [ ] **Step 3: Connect the dashboard entry and global style import**

The existing HVAC page may call `menuStore.ensureLoaded()` for the entry decision, but it must not own administration data. Import `styles/admin.css` exactly once through `style.css`.

- [ ] **Step 4: Run integration-focused frontend tests**

From `web`:

```powershell
npm run test:run -- src/router/index.test.ts src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run build
```

Expected: PASS.

- [ ] **Step 5: Review comments and commit**

Use `code-comment-quality` at high risk for authentication, authorization and controlled dynamic navigation. From repository root:

```powershell
git add -- `
  web/src/router/index.ts `
  web/src/router/index.test.ts `
  web/src/pages/HvacDemoPage.vue `
  web/src/pages/HvacDemoPage.contract.test.ts `
  web/src/style.css
git diff --cached --check
git commit -m "feat(web): integrate administration routes"
```

---

### Task 11: Add a Real Docker Administration Smoke Test

**Files:**
- Create: `scripts/Test-HvacAdminSmoke.ps1`
- Create: `src/test/java/com/platform/config/HvacAdminSmokeScriptContractTest.java`
- Modify: `scripts/Invoke-CleanHvacSmoke.ps1`

**Interfaces:**
- Runs after the existing clean HVAC and WebSocket smoke checks.
- Accepts base URL and credentials as parameters/environment input; never prints secrets or bearer tokens.

- [ ] **Step 1: Write the failing script contract test**

Assert the script:

- is invoked by `Invoke-CleanHvacSmoke.ps1`;
- contains no hard-coded password, bearer token or personal path;
- does not remove volumes or recursively delete directories itself;
- uses the expected administration endpoint paths;
- has explicit cleanup/finally behavior for temporary users and role-menu restoration;
- emits `HVAC_ADMIN_SMOKE_SUCCESS` only after all assertions.

- [ ] **Step 2: Implement reusable authenticated HTTP helpers**

Follow the existing smoke-script conventions. Parse the standard response envelope, fail on non-success codes and redact authorization material from exceptions. Generate one unique temporary username per run.

- [ ] **Step 3: Implement the end-to-end business sequence**

The smoke test must verify, in order:

1. administrator login and `GET /menu/admin/tree`, including hidden/disabled menu visibility;
2. temporary user creation, detail query, four-role assignment and building assignment;
3. temporary-user login and `/menu/current` contains `/hvac-demo` but no management route;
4. disable invalidates the old session, enable plus password reset permits a new login;
5. create, update and delete one hidden leaf menu without exposing a route;
6. submit a building-access request as the temporary user, then approve it as administrator and verify the user detail;
7. capture one non-admin role's menu IDs, replace them to prove navigation changes, verify `/menu/current`, and restore the original IDs in `finally`;
8. delete the temporary user and verify the lifecycle result.

Do not assert CSS or component rendering in this script; browser validation remains Task 12.

- [ ] **Step 4: Wire the smoke into the clean environment runner**

Call the new script only after backend readiness and the existing MQTT/WebSocket checks. Preserve the existing explicit `-ResetData` boundary: the child script must not independently decide to clear volumes.

- [ ] **Step 5: Run contract and real Docker smoke tests**

From repository root:

```powershell
.\mvnw.cmd -Dtest=HvacAdminSmokeScriptContractTest test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-CleanHvacSmoke.ps1 -ResetData
```

Expected markers include the existing clean HVAC success marker and `HVAC_ADMIN_SMOKE_SUCCESS`. If Docker or the environment is unavailable, stop and report this task as unverified; do not mark the feature complete.

- [ ] **Step 6: Review comments and commit**

Use `code-comment-quality` at high risk for credentials, destructive scope, cleanup and role restoration. From repository root:

```powershell
git add -- `
  scripts/Test-HvacAdminSmoke.ps1 `
  scripts/Invoke-CleanHvacSmoke.ps1 `
  src/test/java/com/platform/config/HvacAdminSmokeScriptContractTest.java
git diff --cached --check
git commit -m "test(hvac): add administration docker smoke"
```

---

### Task 12: Run Full Verification and Visual Quality Review

**Files:**
- Modify only files already in this plan when a verified defect requires correction.

- [ ] **Step 1: Run the complete backend gate**

From repository root:

```powershell
.\mvnw.cmd test
```

Expected: all Maven tests PASS with ordinary tests isolated from real MySQL, Redis, TDengine and EMQX.

- [ ] **Step 2: Run the complete frontend gate**

From `web`:

```powershell
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run lint
npm run check
npm run build
```

Expected: all commands PASS.

- [ ] **Step 3: Run repository verification and static UI detection**

From repository root:

```powershell
node .agents/skills/impeccable/scripts/detect.mjs --json
git diff --check
```

Expected: detector findings reviewed and no whitespace errors. Use the `iot-change-verification` matrix to confirm that the focused, full-stack and external-resource checks in this plan cover every changed file type; it has no standalone repository command. Treat detector output as review evidence, not an automatic substitute for browser inspection.

- [ ] **Step 4: Inspect the real browser flow**

Start the Docker-backed application and frontend using documented project commands. In the in-app browser, validate at 1440 px and 1280 px widths:

- administrator login and dashboard entry;
- all four deep links and sidebar active state;
- user create/edit/role/building/reset/status/delete/restore feedback;
- fixed-role menu replacement and immediate navigation refresh;
- hidden/disabled menu maintenance without executable unknown routes;
- direct building assignment and request approval/rejection;
- loading, empty, 401, 403, 404, 409 and network-retry states;
- keyboard focus, table overflow, drawer usability and contrast.

Capture screenshots of the management shell and each page at 1440 px. Mobile-specific adaptation is not an acceptance criterion.

- [ ] **Step 5: Run the finish-design review**

Provide the implementation, screenshots and design document to a fresh `impeccable_finish_reviewer`. Apply material findings in one coherent batch, then rerun the affected focused tests, the complete frontend gate and `git diff --check`. If the reviewer capability is unavailable, record that limitation and complete a manual comparison against the confirmed design instead.

- [ ] **Step 6: Commit verified corrections, if any**

Stage only the files corrected from this plan. Write every corrected path explicitly after reviewing `git diff --name-only`; do not use a placeholder, directory-wide add or glob. Then run:

```powershell
git diff --name-only
git diff --cached --check
git commit -m "fix(web): harden administration ui states"
```

Skip this commit when no correction is needed; never create an empty commit.

---

### Task 13: Materialize Decisions and Update Project Status

**Files:**
- Add: `docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md`
- Add: `docs/superpowers/plans/2026-08-10-hvac-minimal-admin-ui.md`
- Modify: `docs/superpowers/README.md`
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`

- [ ] **Step 1: Materialize the confirmed documents without inheriting planning history**

Read both files from `docs/hvac-admin-management-ui-design` with `git show`, then create the same repository paths in the implementation worktree using `apply_patch`. Do not merge, rebase or cherry-pick the planning branch.

- [ ] **Step 2: Update stable project guidance**

Document:

- the four management route entries and shared layout;
- controlled `/menu/current` navigation and exact frontend allowlist;
- `/menu/admin/tree` as the maintenance tree, distinct from `/menu/tree` and `/menu/current`;
- the absence of building CRUD and continued backend authorization authority.

- [ ] **Step 3: Update current project status using actual evidence**

Move the minimal management UI from “next step” to “implemented” only if the relevant automated tests, Docker smoke and browser flow all passed. Otherwise mark the precise boundary as “部分完成” or “未验证”, including the failed/skipped command and remaining work. Keep WebSocket/history status unchanged unless this branch actually changes it.

- [ ] **Step 4: Update the superpowers index**

Add linked design and plan rows with an implementation result that matches the evidence. Historical documents remain immutable.

- [ ] **Step 5: Run documentation and scope checks**

```powershell
rg -n "hvac-minimal-admin-ui|/menu/admin/tree|/system/users|/system/roles|/system/menus|/system/building-access" PROJECT_GUIDE.md PROJECT_STATUS.md docs/superpowers/README.md docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md docs/superpowers/plans/2026-08-10-hvac-minimal-admin-ui.md
git diff --check
```

Expected: links and claims are consistent and no completed claim exceeds test evidence.

- [ ] **Step 6: Commit project documentation**

```powershell
git add -- `
  PROJECT_GUIDE.md `
  PROJECT_STATUS.md `
  docs/superpowers/README.md `
  docs/superpowers/specs/2026-08-10-hvac-minimal-admin-ui-design.md `
  docs/superpowers/plans/2026-08-10-hvac-minimal-admin-ui.md
git diff --cached --check
git commit -m "docs(hvac): document administration management delivery"
```

---

### Task 14: Perform the Final Scope Audit and Deliver the PR

**Files:**
- No new files; inspect the complete feature branch.

- [ ] **Step 1: Run the final comment-quality audit**

Use `code-comment-quality` across all changed production files:

- high risk: backend authorization, menu hierarchy validation, cache invalidation, password/session handling, role/building replacement and async ownership;
- normal risk: presentation state and error rendering;
- verify comments explain non-obvious authority, side effects and data flow without narrating syntax or claiming future behavior.

- [ ] **Step 2: Audit scope and sensitive material**

```powershell
git status --short
git diff origin/main...HEAD --stat
git diff origin/main...HEAD --name-only
git diff origin/main...HEAD --check
git log --oneline origin/main..HEAD
rg -n -i "password\s*[:=]|bearer\s+[a-z0-9._-]+|token\s*[:=]" scripts web/src docs PROJECT_GUIDE.md PROJECT_STATUS.md
```

Review every search hit manually. Expected: only intentional field names, redacted examples or configuration references; no real credential, token, personal path, generated artifact or unrelated change.

- [ ] **Step 3: Re-run any gate affected by the final documentation/correction commit**

At minimum:

```powershell
.\mvnw.cmd test
cd web
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run lint
npm run check
npm run build
cd ..
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Push the feature branch**

```powershell
git status --short
git push -u origin feature/hvac-minimal-admin-ui
```

Expected: clean worktree and successful push. Do not force-push.

- [ ] **Step 5: Prepare and create the pull request**

Use title:

```text
feat(hvac): add minimal administration management ui
```

The PR body must contain:

- scope: users, fixed roles, controlled menus and building authorization;
- explicit exclusions: building CRUD, dynamic component loading, mobile adaptation, Playwright and visual redesign;
- backend/data changes, including clean-install seed and manual existing-volume migration;
- actual backend, frontend, Docker and browser verification commands/results;
- comment-check risk levels and inspected call chains;
- screenshots for all four pages;
- skipped or unverified items stated plainly.

- [ ] **Step 6: Stop at reviewable delivery**

Report the branch, commits, PR URL, exact verification results and any remaining risk. Do not merge the PR or clean the implementation worktree until the user explicitly reports that the PR has been merged.

---

## Completion Criteria

This task is complete only when all of the following are true:

- the four explicit management routes work from a fresh deep link and the shared shell uses only allowlisted current-menu entries;
- user lifecycle, four-role menu replacement, complete menu-tree maintenance and building authorization operate against real backend APIs;
- backend validation prevents invalid menu hierarchies and preserves security authority;
- clean databases receive the approved menu seed and existing test databases have an explicit migration path;
- automated backend/frontend gates pass;
- the real Docker administration smoke passes;
- desktop browser flows and error states are inspected against the confirmed design;
- project documents distinguish implemented, tested and unverified work accurately;
- the feature branch is pushed and a reviewable PR is created without unrelated changes.
