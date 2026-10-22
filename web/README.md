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

局域网 HTTP 兼容回归（先完成构建，并准备已安装的 Playwright 与 Chromium）：

```powershell
# 若 Playwright 不在本项目依赖目录，设置 PLAYWRIGHT_MODULE_PATH 为其模块路径。
# 使用系统 Edge 时设置 PLAYWRIGHT_BROWSER_CHANNEL=msedge；默认使用 Playwright Chromium。
npm run test:http
```

脚本自动选择本机局域网 IPv4 并使用随机端口，可用 `HTTP_TEST_HOST` 显式指定本机地址。
它检查真实非安全 HTTP 上下文中 `randomUUID` 不可用时的两个分类入口、全部标签页、工作簿清空及设备空间页面；不注入 crypto 补丁。
所有 API 都由浏览器内夹具响应，外部请求和写请求一律拒绝，不连接测试服务器，也不代表真实业务或现场验收。
