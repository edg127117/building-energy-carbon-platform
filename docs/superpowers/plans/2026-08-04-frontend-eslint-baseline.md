# Frontend ESLint Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可执行的 Vue 3 + TypeScript ESLint 实用基线，修复唯一真实存量错误，并让 GitHub 前端 CI 把 lint 作为合并门禁。

**Architecture:** 保留当前 ESLint 8 与传统配置体系，在 `web/.eslintrc.cjs` 集中定义解析链、推荐规则、环境覆盖和生成目录边界。应用代码只修复规则探测确认的 `LoginPage.vue` 显式 `any`；CI 合同测试先锁定 `npm run lint`，再把命令接入前端工作流。

**Tech Stack:** ESLint 8.57.1、eslint-plugin-vue 9.33.0、vue-eslint-parser 9.4.3、typescript-eslint 7.18.0、Vue 3、TypeScript 5.3、Vitest、GitHub Actions、PowerShell 5.1。

## Global Constraints

- 保留 ESLint 8，不升级 ESLint、TypeScript、Vue、Vite 或现有插件大版本。
- 不引入 Prettier，不启用缩进、引号、分号等纯格式化门禁。
- 不修改后端业务、HTTP 接口、页面行为和数据展示。
- 关闭 `vue/max-attributes-per-line`、`vue/singleline-html-element-content-newline`、`vue/html-self-closing` 三条纯模板排版规则，避免 124 条无业务价值警告和全仓模板重排。
- 其余 `eslint:recommended`、`plugin:vue/vue3-recommended`、`plugin:@typescript-eslint/recommended` 规则保持有效。
- 生产 Vue/TypeScript 文件变化时，必须完成人工注释审计并在 PR 中逐符号填写报告。
- Git 操作只处理 `chore/frontend-eslint-baseline`，不得暂存生成目录或无关文件。

---

### Task 1: 建立 ESLint 配置并修复真实存量错误

**Files:**
- Create: `web/.eslintrc.cjs`
- Modify: `web/src/pages/LoginPage.vue:132`

**Interfaces:**
- Consumes: `web/package.json` 中现有 `lint` / `lint:fix` 命令和已锁定的 ESLint 8、Vue、TypeScript 插件。
- Produces: `npm run lint` 可扫描全部 `.ts` / `.vue` 文件并以零错误、零警告退出。

- [ ] **Step 1: 记录缺少配置的失败基线**

Run:

```powershell
Set-Location web
npm run lint
```

Expected: FAIL，退出码 2，包含 `ESLint couldn't find a configuration file`；失败发生在业务代码扫描前。

- [ ] **Step 2: 新增兼容 ESLint 8 的配置**

Create `web/.eslintrc.cjs`:

```javascript
/**
 * 前端静态检查基线。
 *
 * Vue 模板交给 vue-eslint-parser，脚本块再交给 TypeScript 解析器；
 * 纯排版规则不作为 V1 合并门禁，避免 lint 自动重排现有页面模板。
 */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
  },
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module',
    extraFileExtensions: ['.vue'],
  },
  plugins: ['vue', '@typescript-eslint'],
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    'plugin:@typescript-eslint/recommended',
  ],
  ignorePatterns: [
    'node_modules/',
    'dist/',
    'coverage/',
  ],
  overrides: [
    {
      files: ['vite.config.ts', '**/*.test.ts'],
      env: {
        node: true,
      },
    },
  ],
  rules: {
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
  },
}
```

- [ ] **Step 3: 验证配置把噪声收敛为一个真实错误**

Run:

```powershell
npm run lint
```

Expected: FAIL，只有 `src/pages/LoginPage.vue:132` 的 `@typescript-eslint/no-explicit-any` 1 个错误，0 warnings。

- [ ] **Step 4: 用 unknown 和 Error 收窄登录异常**

Replace the catch block in `web/src/pages/LoginPage.vue`:

```typescript
  } catch (error: unknown) {
    const errorMessage = error instanceof Error && error.message
      ? error.message
      : '操作失败'
    message.error(errorMessage)
  } finally {
```

该写法保留 Axios/Error 的真实安全消息；非 Error 值统一使用兜底文案，不再通过 `any` 绕过类型检查。

- [ ] **Step 5: 运行 ESLint 和前端定向回归**

Run:

```powershell
npm run lint
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: lint 0 errors / 0 warnings；Vitest 38/38 通过，0 跳过。

- [ ] **Step 6: 提交 ESLint 基线**

```powershell
Set-Location ..
git add -- web/.eslintrc.cjs web/src/pages/LoginPage.vue
git diff --cached --check
git commit -m "chore(web): establish eslint baseline"
```

Expected: 暂存区只有配置和登录页的等价异常类型修复；仓库 Hook 输出 `REPOSITORY_GUARDRAILS_OK`。

---

### Task 2: 用合同测试把 lint 接入前端 CI

**Files:**
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1:431-438`
- Modify: `.github/workflows/frontend-ci.yml:31-42`

**Interfaces:**
- Consumes: Task 1 产生的稳定 `npm run lint` 命令。
- Produces: 前端 CI 在测试、类型检查和构建之前执行 lint；仓库合同测试防止该步骤被静默删除。

- [ ] **Step 1: 先把 lint 写入 CI 合同测试**

Change the command list in `Invoke-CiContractTests`:

```powershell
    foreach ($command in 'npm ci', 'npm run lint', 'npm run test:run', 'npm run check', 'npm run build') {
        Assert-Contains $frontendText $command "frontend workflow must run $command"
    }
```

- [ ] **Step 2: 运行 CI 合同测试并确认先失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: FAIL，包含 `frontend workflow must run npm run lint`，证明工作流尚未满足新合同。

- [ ] **Step 3: 在前端工作流中增加快速 lint 门禁**

Add after `Install frontend dependencies` in `.github/workflows/frontend-ci.yml`:

```yaml
      - name: Lint frontend sources
        run: npm run lint

      - name: Run frontend tests
        run: npm run test:run
```

保留后续 TypeScript 检查和 Vite 构建步骤，lint 不能替代它们。

- [ ] **Step 4: 运行 CI 合同测试并确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: PASS，输出 `CASE_PASSED: PR template and GitHub Actions contract` 和 `GUARDRAIL_TESTS_PASSED: 1 repository cases`。

- [ ] **Step 5: 提交 CI 门禁和合同测试**

```powershell
git add -- .github/workflows/frontend-ci.yml scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "ci(web): enforce eslint in frontend checks"
```

Expected: 只提交工作流与其合同测试，Hook 通过。

---

### Task 3: 同步项目状态和历史任务目录

**Files:**
- Modify: `PROJECT_STATUS.md:46-52`
- Modify: `docs/superpowers/README.md:52`
- Existing design: `docs/superpowers/specs/2026-08-04-frontend-eslint-baseline-design.md`
- Existing plan: `docs/superpowers/plans/2026-08-04-frontend-eslint-baseline.md`

**Interfaces:**
- Consumes: Task 1 的有效 lint 基线和 Task 2 的 CI 门禁。
- Produces: 当前状态文件可验证地描述已完成能力；历史目录把本任务标记为成对记录。

- [ ] **Step 1: 在工程与运行基础中记录已实现能力**

Add to `PROJECT_STATUS.md` section `3.4 工程与运行基础`:

```markdown
- 前端已建立 Vue 3 + TypeScript ESLint 实用基线，`npm run lint` 会扫描当前 `.ts`/`.vue` 源码，并已进入 GitHub 前端 CI 合并检查。
```

该表述只描述当前 Git 版本可验证的配置和 CI，不记录本机瞬时运行结果。

- [ ] **Step 2: 把目录记录更新为设计/计划成对状态**

Replace the ESLint row in `docs/superpowers/README.md`:

```markdown
| 前端 ESLint 基线与 CI 门禁 | [设计](specs/2026-08-04-frontend-eslint-baseline-design.md) | [计划](plans/2026-08-04-frontend-eslint-baseline.md) | 成对历史记录 | 当前规则和 CI 入口以 `web/.eslintrc.cjs`、`web/package.json` 与前端工作流为准。 |
```

- [ ] **Step 3: 检查文档差异并提交**

```powershell
git diff --check
git add -- PROJECT_STATUS.md docs/superpowers/README.md
git diff --cached --check
git commit -m "docs(status): record frontend eslint gate"
```

Expected: 状态文件只增加当前能力；目录不再标记“仅设计记录”。

---

### Task 4: 完整验证、注释审计和 PR 交付

**Files:**
- Runtime evidence only: `target/`, `web/dist/`, test output（均不得暂存）
- PR description: GitHub PR for `chore/frontend-eslint-baseline`

**Interfaces:**
- Consumes: Tasks 1-3 的配置、代码、合同测试、CI 和文档提交。
- Produces: 完整测试证据、生产文件注释审计、远程任务分支和由 AI 创建的待用户审核 PR。

- [ ] **Step 1: 运行前端完整验证**

```powershell
Set-Location web
npm run lint
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run build
Set-Location ..
```

Expected: lint 0 errors / 0 warnings；Vitest 38/38 通过；类型检查和构建通过；仅允许记录既有大 chunk 警告。

- [ ] **Step 2: 运行仓库防护和后端完整回归**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
mvn test
```

Expected: 防护测试输出 `GUARDRAIL_TESTS_PASSED: 18 local cases` 和 `GUARDRAIL_TESTS_PASSED: 11 repository cases`；Maven 441/441 通过，0 跳过。

- [ ] **Step 3: 生成人工注释审计报告**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
```

Expected: 报告至少覆盖 `web/src/pages/LoginPage.vue` 的全部可识别函数。逐项填写固定判定枚举和具体业务说明；`onSubmit` 的异常类型收窄不改变登录/注册流程，已有注释必须核验为当前行为。

- [ ] **Step 4: 检查分支范围、残留和冲突**

```powershell
git status --short --branch
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
git log --oneline origin/main..HEAD
git merge-tree (git merge-base HEAD origin/main) HEAD origin/main
```

Expected: 只有 ESLint 设计/计划、配置、登录页等价修复、CI/合同测试和状态文档；没有 `node_modules`、`dist`、日志、凭据或冲突标记。

- [ ] **Step 5: 推送任务分支**

```powershell
git push -u origin chore/frontend-eslint-baseline
```

Expected: 远程分支创建成功，不更新远程 `main`。

- [ ] **Step 6: 由 AI 创建 PR 并等待用户审核合并**

Create a non-draft PR:

- Base: `main`
- Compare: `chore/frontend-eslint-baseline`
- Title: `chore(web): 建立 ESLint 基线与 CI 门禁`
- URL: `https://github.com/edg127117/iot-platform-demo/compare/main...chore/frontend-eslint-baseline?expand=1`

PR description must include:

- 初始原因：脚本和依赖存在，但配置及 CI 调用缺失；
- 规则边界：保留推荐规则，关闭三条纯模板排版规则；
- 唯一生产代码修复：`LoginPage.vue` 将 `any` 收窄为 `unknown` / `Error`；
- 全部测试命令、准确通过数量、警告和跳过项；
- 生成器要求的完整 `comment-audit` 文件标记、元数据、固定判定枚举和全部符号行；
- 无关文件检查和 `main` 冲突状态。

Expected: PR 页面显示可合并，GitHub 的 Backend CI、Frontend CI、Repository Guardrails 全部通过后，状态为“等待用户审核并合并”。
