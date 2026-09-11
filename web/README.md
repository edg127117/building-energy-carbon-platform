# 建筑能碳监测管理平台前端

当前前端以 Vue 3、TypeScript、Element Plus 和 ECharts 实现，`index.html` 是唯一默认入口。

```powershell
npm ci
npm run dev
```

常用入口：

- 系统选择：`#/systems`
- 暖通监控：`#/operations/realtime/hvac`
- 趋势对比：`#/operations/energy/trend`
- 监控端：`#/monitor/monitoring`
- 登录：`#/login`

开发时使用同源 `/api`，或设置 `VITE_API_BASE` 指向允许当前来源的后端；实时地址可用 `VITE_WS_BASE` 配置。导航由后端当前菜单授权，角色不会自动授予全部页面。业务接口、页面状态和敏感写审核须另外联调。

提交前执行：

```powershell
npm run test:run
npm run lint
npm run check
npm run build
```

目录和依赖边界见 [`AGENTS.md`](AGENTS.md)，当前状态见仓库根目录的
[`PROJECT_STATUS.md`](../PROJECT_STATUS.md)。
