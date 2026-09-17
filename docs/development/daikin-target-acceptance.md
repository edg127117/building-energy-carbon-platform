# 大金专用双库与隔离端到端验收

## 本次结果（2026-09-17）

核心隔离软件链路和本轮客户可见文案检查通过；真实厂家等外部项仍未验证。本记录属于活动候选证据，不提升为现场验收或正式生产基线。

| 验证 | 实际结果 |
| --- | --- |
| MySQL 8.4.11 空库正式迁移、TDengine 3.2.3.0 JDBC 基础验证 | `EnergyLoop7MysqlIntegrationTest,EnergyLoop7TdengineIntegrationTest`：2 项通过，0 跳过；47 个迁移执行到 V55 |
| 普通大金与测试 Redis 回归 | `Daikin*,TestRedisIsolationTest`：135 项中 133 项通过、0 失败；专用双库 2 项因未显式启用而跳过 |
| 真实浏览器完整流程 | `Verify-DaikinTargetBrowser.cjs` 从 reset 开始运行，`resumed=false`，6 组断言通过，无页面脚本错误 |
| 专用双库存储专项 | 显式启用 `DaikinTargetStorageVerificationTest`：2 项通过，0 失败、0 跳过 |
| 同步主分支后的接入与双库复验 | `DaikinTargetStorageVerificationTest,DaikinOnboardingIntegrationTest,DaikinTemperatureBindingIntegrationTest,DeviceOnboardingServiceIntegrationTest`：22 项通过，0 失败、0 跳过 |
| 环境创建脚本 | 在独立临时目录实际创建全新双库，输出 `DAIKIN_TARGET_ENGINES_READY`；就绪探测兼容 Windows PowerShell 5 |
| 脚本与差异检查 | PowerShell 解析、Node 语法检查、`git diff --check` 通过 |

浏览器覆盖真实账号登录与菜单、页面触发目录同步、生产服务审批绑定、分钟采集、设备搜索、0℃／36℃／24.5℃ 历史曲线、温度与状态时间戳一致、运行统计、故障出现与恢复、状态事件、业主建筑范围、匿名拒绝及 91 天查询拒绝。绑定和激活复用生产审批服务，由测试控制面推进；不是逐按钮验收审批页面。运行统计由测试驱动唤醒生产调度，不代表已守候真实凌晨 03:00 或完成长期运行。

存储专项覆盖 90 天前温度删除与近期温度保留，1 年前状态事件、已恢复异常、运行统计值／修订／任务清理，以及未恢复异常和近期统计保留。重试验证锁定同一个失败任务及周期，核对 `RETRY_WAIT`、重试次数和恢复后的成功值。

桌面与窄屏完整截图确认温度单位可见，36℃ 对应自动扩展到 40℃ 的刻度，0℃ 保持真实读数。目录同步和运行统计任务状态已按完整状态集合显示中文，未知目录状态显示“同步状态待确认”；目录同步默认摘要改为中文状态与处理结果，提交成功后清空数据源技术标识，完整数据源标识、任务编号和失败代码收进折叠的运维技术详情，不再直接暴露后端枚举和长编号。待接入设备页使用“大金空调接入／其他设备接入”内容页签，操作名称为“开始目录同步／更新任务状态”；最新一次后台任务以紧凑的“最新同步结果”状态栏反馈，不作为同步历史列表。390px 浏览器视口未出现横向溢出。

验收中修正了测试环境时区、测试账号职责和菜单配置，以及测试重置时删除子表导致的建表缓存冲突。正式环境仍应核对应用、MySQL 会话和 TDengine 的时区一致性，并配置实际账号菜单／建筑范围。

## 验证边界

使用真实 MySQL 8.4、TDengine 3.2.3.0、Spring Boot 后端、Vue 前端和浏览器。仅厂家侧使用测试 provider；目录、审批、绑定、采集、持久化、查询和业务鉴权执行生产代码。Redis 使用既有测试实现，本验收不覆盖真实 Redis。

测试启动类与控制面仅存在于 `src/test/java`，不进入生产 JAR。必须使用独立容器、显式隔离开关和 loopback 地址。禁止把配置替换为业务数据库；禁止将 `target/daikin-target-env.json` 中的临时凭据提交到仓库或验收报告。

本验证不确认厂家请求签名、实际报文、项目授权、现场设备、网络、限流或长期运行。运行统计中的 `totalRuntime/minute` 为测试契约，不表示已经确认厂家单位。

## 重现流程

在仓库根目录使用 Java 21、Node.js、Docker 和已安装的 Playwright。Docker 不在 PATH 时，为两个 PowerShell 脚本传入 `-DockerPath`。浏览器脚本支持 `DAIKIN_PLAYWRIGHT_MODULE` 指定已有 Playwright 模块路径，不下载依赖。

1. 创建专用容器：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-DaikinTargetEnvironment.ps1
   ```

   容器使用唯一名称和 `codex.task=daikin-target-e2e` 标签，仅绑定本机随机端口。配置和随机测试密码写入忽略目录 `target/`。已有环境文件时拒绝覆盖。MySQL 会话与 TDengine 容器均按北京时间运行，与项目部署配置一致；浏览器还检查状态和温度的毫秒时间是否一致，防止仅因数值正确而漏判时区偏移。

2. 启动后端：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-DaikinTargetBackend.ps1
   ```

   脚本核对容器身份、标签及 JDBC 端口，编译测试启动器后以前台进程运行。Flyway 执行正式 MySQL 迁移，TDengine 初始化正式时序表。

3. 在另一个终端启动前端：

   ```powershell
   cd web
   $env:VITE_API_BASE='http://127.0.0.1:18089/api'
   npm.cmd run dev -- --host 127.0.0.1 --port 5180 --strictPort
   ```

4. 在仓库根目录执行浏览器验收：

   ```powershell
   node scripts/Verify-DaikinTargetBrowser.cjs
   ```

   默认使用本机 Edge；可通过 `DAIKIN_BROWSER_CHANNEL` 指定已有浏览器通道。截图与脱敏结果写入 `target/daikin-target-browser/`。脚本通过登录页面获取真实会话，不拦截业务 API、不注入伪造 Token。页面提交目录任务后，测试驱动推进已入队任务；绑定与激活由测试入口调用既有审批服务，未自动化逐个点击审批页面。

5. 停止独立后端后运行存储专项，避免两个后端同时修改测试数据：

   ```powershell
   $env:DAIKIN_TARGET_VERIFY='true'
   .\mvnw.cmd '-Dtest=DaikinTargetStorageVerificationTest' test
   Remove-Item Env:DAIKIN_TARGET_VERIFY
   ```

   此测试读取同一专用环境，验证保留期和失败重试。普通 `mvn test` 不启用它，也不连接目标数据库。

## 结果解释

- `DAIKIN_TARGET_ENGINES_READY` 只表示专用容器就绪。
- `DAIKIN_REAL_BROWSER_E2E_OK` 表示浏览器脚本所列场景通过，不等于真实厂家联调通过。
- 存储专项必须检查 Surefire 实际失败、错误和跳过数量；被跳过不属于通过。
- 数据库迁移验证针对专用测试库，不代替生产存量数据迁移、备份恢复或长期容量验证。
- `reset` 只清理该测试来源的观测与统计，保留目录和绑定以支持重复执行。清理环境时只处理环境文件记录且标签核对一致的专用容器；现有业务容器不在范围内。
