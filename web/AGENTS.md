# 前端边界

唯一入口为 `index.html → src/app/main.ts`。以已批准的[前端实施计划](../docs/designs/frontend-visualization-phase-two-implementation-plan.md)为基线；旧入口、旧页面、Ant Design 和 Tailwind 实现已退出。

- 代码只进入 `app/modules/shared/infrastructure/generated/locales/styles`；不得恢复旧页面、旧全局样式或旧请求客户端。
- 页面属于模块；跨模块仅导入 `public.ts`。请求放模块 `api`，传输放 `infrastructure/http`，权威业务计算留后端。
- Element Plus 与 Lucide 经 `shared/ui` 使用，ECharts 经 `shared/charts` 使用；不复制实现基础控件。
- 同类 UI 第三次出现时必须优先抽象复用；不创建只透传属性的空壳。
- 样式采用 `--bec-` Token，局部样式必须 scoped；中文文案进入统一注册与模块语言资源。
- 大屏只增加自己的页面和注册信息，禁止复制外壳、缩放与图表生命周期。
- 监控弹窗、提示框和遮罩必须留在逻辑画布坐标系，不使用默认挂到 body 的浮层配置。
- 未确认的页面内容、指标、分组、排序、权限和刷新规则不自行填充；测试替身不能进入业务页面。
- 开发迭代先执行与改动直接相关的定向测试或检查；提交和 PR 前运行 `npm run test:run`、`npm run lint`（含边界检查）、`npm run check`、`npm run build`，并确保唯一入口能够构建。只有代码继续变化、检查失败或风险未解决时才重复完整矩阵；纯文档按根规则验证。
