---
name: iot-change-verification
description: Verify changes in iot-platform-demo, including Java and Spring Boot backend code, Vue and TypeScript frontend code, tests, configuration, APIs, cross-layer contracts, IoT data flows, repository process files, and documentation. Use whenever a task changes this repository and the required validation must be selected, executed, and reported.
---

# IoT Change Verification

## Purpose

根据当前差异选择最小但充分的验证矩阵。只报告本次实际执行的命令和结果，不复用历史测试数量，也不把局部验证表述为端到端完成。

## Workflow

1. 读取仓库 `AGENTS.md`，检查 `git diff`、受影响模块及其现有测试。
2. 按下表确定验证范围；涉及多类变化时合并执行，不相互替代。
3. 先运行最接近变化的定向检查，再运行该类要求的完整门禁。
4. 失败时保留首个有效错误，区分代码失败、环境失败和未执行项；不得通过跳过测试制造成功。
5. 交付时列出命令、结果、失败或跳过原因，以及自动化未覆盖的人工确认。

## Validation Matrix

### Java / Spring Boot

- 生产 Java 代码：先运行相关测试类或模块，再在仓库根目录运行 `.\mvnw.cmd test`。
- `pom.xml`、打包、插件、Java 版本或 CI 变化：补充 `.\mvnw.cmd -DskipTests package`；必要时核对 `docs/development/java21.md`。
- 数据源、事务、权限、MQTT、WebSocket、定时任务、缓存或初始化变化：必须验证测试环境隔离，并覆盖失败路径和边界条件。

### Vue / TypeScript

在 `web/` 目录执行：

1. 与变化最接近的 Vitest 用例；
2. `npm run test:run`；
3. `npm run lint`；
4. `npm run check`；
5. `npm run build`。

纯样式变化仍至少运行 lint、类型检查和构建；交互、状态、接口或数据映射变化必须运行相关测试。

### Cross-layer contracts

字段、路径、权限、状态码、时间范围、时区、单位、精度或枚举语义变化时，同时验证：

- 后端 DTO、Controller、Service 与对应测试；
- 前端 API 类型、映射、状态处理与对应测试；
- 文档或硬件契约是否需要同步；
- 缺失值、越权、空数据、失败和兼容路径。

### External resources and IoT paths

- 普通自动化测试不得连接真实 MySQL、TDengine、Redis、MQTT Broker、现场设备或第三方服务。
- 使用 Mock、Stub、测试配置或专用测试容器；现场或集成验证必须单独标记环境、前置条件和未覆盖范围。
- MQTT、采集、聚合、缓存、实时发布等链路变化，应按“入口 → 持久化/计算 → 查询 → 展示”核对数据身份、建筑范围、时间语义和单位。

### Tests, docs, scripts, and repository process

- 测试代码变化：运行被修改测试及其直接覆盖对象；若改变公共测试基础设施，运行受影响的完整测试集。
- 纯文档：检查 Markdown 链接、术语一致性和 `git diff --check`，不机械运行无关业务测试。
- PowerShell、Hook、CI、PR 模板或 Guardrail：运行 PowerShell 解析、对应脚本测试、关键契约检查和 `git diff --check`。
- 配置变化：运行相应解析/加载检查，并确认示例值不含凭据、环境专用地址或未脱敏数据。

## Reporting

按“已执行并通过 / 已执行但失败 / 未执行及原因 / 仍需人工确认”四类汇报。测试失败或关键门禁未运行时，不得将分支描述为可合并。
