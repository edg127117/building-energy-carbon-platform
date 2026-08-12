# HVAC 公式计算详情前端接入设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。

> 状态：2026-08-04 已由用户通过工作树交接文档确认，当前任务按此设计实施。

## 目标

让用户从 HVAC 大屏四项实时指标卡进入对应来源分钟的计算详情，查看后端返回的结果、数据质量、公式版本、输入证据、分步过程，以及失败原因和缺失语义键。

前端只解释后端已经产生的事实，不执行公式、不补模拟值，也不使用当前公式解释旧版本结果。

## 范围

本次完成：

- 计算详情 DTO 与 API 客户端；
- 独立详情 Composable，负责打开、关闭、重试、错误映射和竞态隔离；
- 独立详情抽屉，覆盖加载、成功、业务失败、无数据和 HTTP 错误；
- 四项实时指标卡入口、建筑切换清理和卸载清理；
- API、Composable、组件和页面契约测试；
- `PROJECT_STATUS.md` 状态同步。

本次不完成历史曲线、WebSocket、后台管理、公式修改、数据库修改、后端 API 或全局 HTTP 行为调整。

## 架构

采用 `types/hvac.ts → api/hvac.ts → useHvacCalculationDetail.ts → HvacCalculationDetailDrawer.vue → HvacDemoPage.vue` 的单向依赖。

- 类型层只描述 `HvacIndicatorDtos.CalculationDetail` 协议。
- API 层只请求 `/hvac/indicators/{indicatorId}/calculations/{minuteStart}`；统一客户端已经提供 `/api` 基地址。
- Composable 清空旧详情后发起请求，并用递增请求版本阻止迟到响应覆盖新目标或已关闭抽屉。
- 抽屉只格式化和展示后端字段，向上发出 `close`、`retry` 事件。
- 页面只把 `DashboardIndicatorView` 转为详情目标，切换建筑或卸载时关闭抽屉。

## 数据与状态

详情字段与后端 `HvacIndicatorDtos.CalculationDetail` 一致：指标身份、设备、来源分钟、状态、结果、单位、质量、公式版本、输入、步骤、原因码和缺失项。

质量等级固定展示为 Q0 真实数据、Q1 线性插值、Q2 典型值；未知等级保留原值。状态覆盖 `SUCCESS`、`MISSING_INPUT`、`INVALID_INPUT`、`ENGINE_ERROR`、`NO_DATA` 和未知后端状态。

没有 `indicatorId` 或 `minuteStart` 时打开本地无记录状态，不发送非法请求。HTTP `403`、`404`、`409`、`503` 使用专用业务文案；其他失败使用安全通用文案，不显示 SQL 或连接信息。

## 交互与视觉

四张指标卡改为可聚焦按钮，并提供可见的“查看计算详情”提示、hover 和 `focus-visible` 状态。抽屉沿用现有深色 HVAC 视觉语言，桌面宽度约 600px，小屏不超过视口宽度。

抽屉按标题、摘要、输入、步骤、失败审计、请求错误的顺序展示；状态不能只靠颜色表达。数字使用表格数字或等宽字体，长表达式允许换行，空单位不能渲染为 `null` 或 `undefined`。

## 测试

- API：精确路径与 `indicatorId` URL 编码。
- Composable：成功、本地无目标、错误、重试、关闭、A/B 竞态、关闭后迟到响应。
- 组件：成功、四类业务失败/无数据、HTTP 错误、事件、长表达式和空单位。
- 页面契约：真实入口与抽屉存在，旧占位删除，无伪造详情，历史曲线仍未接入。
- 回归：前端全量测试、类型检查、Lint、构建和 `mvn test`。

## 完成边界

生产代码改动后按仓库规则生成并人工完成注释审查报告。真实 Docker 与浏览器验收只使用仓库现有环境；若环境缺失或需要用户账号，将如实标记未验证，不污染初始化数据。
