# 创新港月度测试后端的本机运行

本入口管理已准备好的 Windows 本机独立实例，供电力月度历史数据和模拟数据测试使用。它不创建数据库、不重新导入数据、不执行迁移，也不连接共享服务器。图表接口和大屏 UI 不在本步骤范围。

月度测试数据现已迁入[服务器独立实例](cxg-server-demo-environment.md)，后续测试使用服务器 19080 入口。本机实例停止并作为迁移前副本保留；下文命令保留用于按需检查源副本，不应同时在两端继续日常写入。

## 固定目录与入口

实例位于 `$env:USERPROFILE\.building-energy-carbon\cxg-monthly-test`，与 Git 工作树分开。当前已安装的 Python、Java 21、MySQL、Redis 路径记录在私有实例清单；TDengine 继续使用该实例专属容器和数据卷。运行前先启动 Docker Desktop。

在本机 Windows PowerShell 执行：

```powershell
$testRoot = Join-Path $env:USERPROFILE '.building-energy-carbon/cxg-monthly-test'
$testEntry = Join-Path $testRoot 'bin/Invoke-CxgMonthlyTest.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File $testEntry -Action Start
powershell -NoProfile -ExecutionPolicy Bypass -File $testEntry -Action Status
powershell -NoProfile -ExecutionPolicy Bypass -File $testEntry -Action Check
powershell -NoProfile -ExecutionPolicy Bypass -File $testEntry -Action Restart
powershell -NoProfile -ExecutionPolicy Bypass -File $testEntry -Action Stop
```

以上分别为独立操作，按需执行。`Start` 可重复调用；`Stop` 保留全部数据库和容器卷，并保存 Redis 缓存；`Restart` 先停止再启动四个组件。请在没有正在执行的导入、审核或封账请求时停止实例。没有自动开机启动任务。

`Status` 只显示实例地址、组件状态、建筑 ID、默认月份和账号名称，不显示密码。实际地址及数据范围见运行目录的 `environment-info.json`，不要从共享平台地址推断测试实例地址。

## 账号、配置与数据

- `private/instance.json` 保存路径、固定端口、凭据、已验收 JAR 摘要和月度核对基线；不得提交 Git 或放入普通交接包。
- `private/accounts.json` 保存本机测试管理员和审核账号的登录信息。该目录仅授予当前用户和 SYSTEM 访问，使用现有账号，不向共享平台新增账号或授权。
- `backend/application.jar` 是已验收的固定程序包；`config/` 是该实例自己的配置，启动不依赖 `target/` 或工作树中的测试资源。
- `data/mysql`、`data/redis` 和专属 TDengine 数据卷保存现有测试数据；日志写入 `logs/`。没有重置、清空或删除数据卷的命令。

测试数据仍为电力月度口径，保留原始资料及来源标识。`Check` 核对本轮已验收数据集的月度结果、冻结快照、TDengine 数值与证据摘要，以及三个对比月份的用量、折标、排放和冻结面积强度；它不是通用的数据导入接口。后续修改这组测试用例时应明确更新核对基线，不能以清库掩盖差异。

原工作树内的数据副本作为迁移留存保留，不再使用旧的临时启停脚本。工作树清理不会删除固定运行目录或专属 TDengine 卷；代码更新不会自动替换实例中的已验收 JAR。

## 隔离与后续前端接入

后端、MySQL、Redis、TDengine REST 均仅绑定本机回环接口。管理脚本核对进程参数、数据目录、容器身份、卷及端口；遇到端口被占用或资源身份不符会报告错误，不会终止其他进程、改用其他数据库或自动更换端口。系统重启后，已被其他程序复用且未占用本实例端口的旧 PID 不会被终止。

MQTT、厂家客户端、设备运行采集、控制、天气、自动补全和后台重算关闭。后端启动使用受控环境变量，避免当前终端的其他数据源或厂家配置改变该实例目标。

前端现有 `VITE_API_BASE` 可用于选择接口地址，但浏览器还受同源策略约束。后续前端接入阶段应按其开发服务器配置同源代理或明确的跨域来源；本步骤不修改前端、代理或图表接口。当前入口供本机使用，不是新的局域网发布地址，也不是正式生产环境。

## 排查

- Docker 不可用：先恢复 Docker Desktop，禁止通过复用共享 TDengine 来绕过检查。
- `PORT_OCCUPIED` 或 `PROCESS_IDENTITY_MISMATCH`：先查清端口和进程，不要关闭身份不明的服务。
- `BACKEND_JAR_CHANGED`、`CONFIG_TARGET_MISMATCH`：检查固定包及私有实例清单，避免把开发目录中的新包当作已验收包自动启动。
- 组件启动失败：查看对应 `logs/backend.log`、`logs/mysql.log` 或 `logs/redis.log`，不把完整凭据和日志复制到公开交接资料。

离线验证：`python -m unittest discover -s scripts/tests -p test_cxg_monthly_env.py -v`。实际本机生命周期验证独立执行，不用单元测试代替启停后的数据核对。
