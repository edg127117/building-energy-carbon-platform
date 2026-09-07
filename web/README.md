# 建筑能碳监测管理平台前端

当前前端以 Vue 3、TypeScript、Element Plus 和 ECharts 实现，`index.html` 是唯一默认入口。

```powershell
npm ci
npm run dev
```

常用入口：

- 办公端：`#/office/dashboard`
- 趋势分析：`#/office/trends`
- 监控端：`#/monitor/monitoring`
- 登录：`#/login`

提交前执行：

```powershell
npm run test:run
npm run lint
npm run check
npm run build
```

目录和依赖边界见 [`AGENTS.md`](AGENTS.md)，当前状态见仓库根目录的
[`PROJECT_STATUS.md`](../PROJECT_STATUS.md)。
