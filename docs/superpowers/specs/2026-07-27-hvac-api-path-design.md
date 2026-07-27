# HVAC 接口重复 `/api` 路径修复设计

## 背景与问题

项目通过 `server.servlet.context-path: /api` 为全部 HTTP 接口统一添加
`/api` 外部前缀。`HvacQueryController` 又在类级映射中声明了
`/api/hvac/buildings`，因此真实运行时的 HVAC 接口变成了错误的
`/api/api/hvac/buildings/**`。

现有 `HvacQueryControllerFlowTest` 直接把 `/api/hvac/**` 当作
Controller 内部映射执行，没有为 MockMvc 请求声明 `/api` context path，
所以测试环境没有复现真实服务器的路径拆分，也没有发现重复前缀。

## 目标

- HVAC 快照接口对外使用
  `GET /api/hvac/buildings/{buildingId}/snapshot`。
- HVAC 历史接口对外使用
  `GET /api/hvac/buildings/{buildingId}/history`。
- 错误路径 `/api/api/hvac/**` 直接废弃，不提供别名、重定向或代理兼容。
- 保持现有权限、参数校验、业务服务和响应结构不变。

## 修复方案

将 `HvacQueryController` 的类级映射改为 `/hvac/buildings`。该路径只表达
HVAC 模块在 Spring MVC 内部的业务路由；外部统一前缀继续由全局
`server.servlet.context-path` 提供。

不移除全局 context path，也不修改其他 Controller，因为这会扩大影响范围
并破坏当前已经使用 `/api/**` 的其他外部接口。前端当前没有 HVAC 查询客户端，
冻结设计文档中记录的外部路径已经是正确的 `/api/hvac/**`，因此本次不修改前端。

## 测试策略

调整 HVAC Controller 流程测试，使每个 HVAC 请求同时满足：

- 请求 URI 包含外部前缀 `/api`；
- MockMvc 请求明确设置 `contextPath("/api")`；
- Controller 实际接收的 servlet path 为 `/hvac/**`。

保留原有认证、角色、建筑数据范围、参数校验、404 和 TDengine 503 测试。
新增回归断言：已认证用户请求 `/api/api/hvac/**` 时返回 404，确保错误路径没有
被兼容映射继续暴露。

完成定向测试后执行 `mvnw verify`，确认全部后端测试和打包通过。

## 改动范围

本次只修改：

- `HvacQueryController` 的类级路径映射及相关用途注释；
- `HvacQueryControllerFlowTest` 的 context path 模拟和重复路径回归测试；
- 本设计文档与后续实施计划。

不修改 Service、Repository、数据库结构、权限规则、前端请求封装、Nginx 配置
或其他业务接口。
