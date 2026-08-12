# 前端 ESLint 基线与 CI 门禁设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。

> 状态：2026-08-04 已由用户确认采用“实用基线”方案；本文仅冻结设计，实施计划和代码改动须在用户复核本文后继续。

## 问题与原因

`web/package.json` 从仓库首次导入起就声明了 `lint`、`lint:fix` 命令，并安装 ESLint、Vue 与 TypeScript 解析插件，但仓库没有提交 `.eslintrc.*` 或 `eslint.config.*`。因此 `npm run lint` 会在扫描业务代码前以退出码 2 失败，错误为找不到配置文件。

现有前端 CI 只执行测试、类型检查和构建，没有运行 lint。`vue-tsc`、Vitest 和 Vite 不会自动替代 ESLint，所以这个初始导入缺口一直没有形成 CI 失败，也不是某个 HVAC 功能改动造成的。

## 方案选择

采用保留 ESLint 8 的实用基线：补齐 Vue 3 + TypeScript 配置、处理当前存量违规并将 lint 接入 CI。

不采用以下方案：

- 仅添加能让命令启动的最小配置，因为它不能形成有效质量门禁；
- 同时升级 ESLint 9 和扁平配置，因为依赖升级、规则迁移和当前治理目标耦合，扩大了 V1 稳定化风险。

## 范围

本次完成：

- 新增 `web/.eslintrc.cjs`，兼容当前 ESLint 8 和 `type: module`；
- 配置 Vue 单文件组件和 TypeScript 解析链；
- 启用 ESLint、Vue 3 和 TypeScript 的推荐规则；
- 忽略依赖、构建和覆盖率产物；
- 修复当前基线暴露的真实违规，或对明确不适用、产生误报的规则做有理由的窄化配置；
- 在前端 CI 中执行 `npm run lint`；
- 更新项目状态和本目录索引。

本次不升级 ESLint、TypeScript、Vue 或 Vite，不引入 Prettier，不建立纯格式化规则，不修改后端业务，不借 lint 修复重构页面。

## 配置设计

使用 `.eslintrc.cjs`，避免 `web/package.json` 的 `type: module` 将传统 CommonJS 配置误解释为 ESM，同时保持现有 `eslint . --ext .ts,.vue` 命令不变。

解析链：

- Vue 文件由 `vue-eslint-parser` 解析模板和脚本块；
- TypeScript 脚本由 `@typescript-eslint/parser` 解析；
- 环境覆盖浏览器、ES2022 和 Node.js 配置文件需求；
- 检查 `.ts`、`.vue` 以及现有测试文件，不扫描 `node_modules`、`dist`、`coverage` 等生成目录。

规则基线采用 `eslint:recommended`、Vue 3 推荐规则和 TypeScript 推荐规则。规则调整遵循：

1. 能通过删除死代码、修复错误作用域或改善明确类型解决的，修改代码；
2. 框架入口、Vue 模板或既有测试模式造成误报时，使用文件级 override 或单条规则配置；
3. 不为了“零报错”关闭整组推荐规则；
4. 不启用缩进、引号、分号等纯格式规则，避免无业务价值的全仓改写。

## 存量问题处理

首次有效运行 lint 后，按“真实缺陷、可维护性问题、框架误报、纯风格噪声”分类。真实缺陷和可维护性问题在本任务修复；误报通过最小配置处理；纯风格噪声不进入当前基线。

生产 Vue/TypeScript 文件只有在当前规则确实发现问题时才修改。所有修改保持行为等价，不改变接口、页面功能、请求流程和数据展示。

## CI 与失败语义

在 `.github/workflows/frontend-ci.yml` 的依赖安装后增加 `npm run lint`，使语法、Vue 模板和 TypeScript 静态规则失败能够尽早阻止 PR。测试、类型检查和构建继续保留，各自覆盖不同质量维度。

CI 失败时必须修复代码或给出可审计的规则边界，不能使用忽略整个目录、批量 disable 或跳过 lint 的方式绕过。

## 测试与验收

至少执行：

- `npm run lint`；
- `npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads`；
- `npm run check`；
- `npm run build`；
- 仓库完整回归 `mvn test`。

若生产 Vue/TypeScript 文件发生变化，还要生成并人工完成完整注释审计报告。最终 PR 必须证明 lint 在本地和 GitHub 前端 CI 中均通过，并如实记录警告、跳过项和未验证内容。

## 完成边界

完成后，`npm run lint` 必须实际扫描当前前端代码并以零错误退出；CI 必须把 lint 作为合并门禁，而不是只存在一个未调用的脚本。任务分支只包含 ESLint 基线、必要的等价修复、测试/CI 和对应文档，不包含无关格式化或功能开发。
