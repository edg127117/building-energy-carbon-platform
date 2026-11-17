# 大屏第一版归档：screen-v1.0.0

归档日期：2026-11-17。用户批准将包含三维加载优化的大屏第一版对齐远程主线，作为后续开发起点。

## 版本身份

| 项目 | 固定标识 |
|---|---|
| 仓库 | `edg127117/building-energy-carbon-platform` |
| 版本标签 | `screen-v1.0.0`，发布后不移动、不覆盖 |
| 归档目标 | 本次归档 PR 合入 `main` 后的合并提交，精确提交号由附注标签固定 |
| 原主线 | `c6bebbe189849066d8afd09cb347d863351b5e72`，包含 PR #157、#158、#159 |
| 三维优化来源 | `0575700f03a06e9f41925bbf4711a092408cfbf2`；PR #160 合入功能分支后的提交为 `45a9f0bd8b4c607f3516c6abbd2bb15593aa21b1` |
| 页面入口 | `/monitor/monitoring`，由[大屏注册表](../../web/src/modules/large-screen/registry/screens.ts)指向 `FinalBPage` |

远程标签存在且目标属于 `main` 后，才算完成留档。标签发布前本文件是归档候选说明。此版本号仅用于大屏阶段交付，不改动 Maven/npm 的工程版本，也不表示平台整体符合 GB/T 47474—2026。

## 冻结范围与限制

- 保留原主线全部代码，包含大屏年度/月度/日及小时监测、年日查询修复、小时更新与历史日首次加载优化，以及空调、电梯、照明专业下钻展示。
- 纳入三维静态模型与固定景观准备复用、建筑与渲染脚本并行读取、外观先显示和空调延后准备；场景运行资源独立释放，退出或切换账号清理静态缓存，失败允许重试。
- 主大屏读取后端监测结果；专业下钻左右面板中的固定样例仍仅用于展示，不作为真实设备状态或能耗证据。监测展示不替代正式能碳核算。
- 保留建筑授权、专业规则确认、Q0/Q1/Q2 场景策略、原始证据、来源版本、缺失与真实零值区分及环境隔离。当前新平台仍限定监测、分析和展示，不因归档扩大控制权限或范围。
- 既有记录中的百表输入至页面约 5 秒验收使用独立环境合成仓库输入，不代表真实设备/MQTT、公网慢网或长期容量承诺。三维无缓存首开 3 秒目标未全面通过；真实设备、长期驻屏和现场验收未完成。原验收范围见[项目状态](../../PROJECT_STATUS.md)，不能将历史结果表述为本次重新实测。

## 代码与运行环境分别记录

| 对象 | 本次留档所能确认的内容 |
|---|---|
| `main` 与标签 | 通过归档 PR 对齐源码；标签固定合并后的第一版 |
| 19080 独立演示环境 | 三维优化来源提交的状态记录明确记载已发布独立环境及其公网转发入口；本次未重新读取服务器实际包、哈希或数据库，不能据此证明当前运行包与标签逐字节一致 |
| 18080 页面 / 18081 API 共享测试环境 | 本次不部署；上述大屏及三维优化记录注明共享环境保持不变，不视为自动升级至第一版 |

本归档保存 Git 中的源码、锁文件、迁移脚本及非敏感构建配置，不包含运行数据库、私密配置、凭据、已部署 JAR、镜像或服务器静态资源备份。Git 回读不能代替整套环境恢复。

19080 构建必须使用仓库的 `npm --prefix web run build:demo`，并通过 `node scripts/build-cxg-demo-frontend.mjs --verify-only` 核验建筑绑定。依赖安装使用锁文件；后端遵循 [Java 21 指南](../development/java21.md)。完整部署边界见[独立演示环境说明](../development/cxg-server-demo-environment.md)。这些是重建入口，不承诺未来依赖源可用或构建产物字节完全一致。

## 下一阶段从哪里开始

在干净工作区获取远程主线与标签，确认继承关系，再创建正常任务分支：

```powershell
git fetch origin main
git fetch origin tag screen-v1.0.0
git merge-base --is-ancestor screen-v1.0.0 origin/main
if ($LASTEXITCODE -ne 0) { throw '主线未包含大屏第一版，停止创建任务分支' }
git switch -c feature/next-task origin/main
if ($LASTEXITCODE -ne 0) { throw '任务分支创建失败' }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

`feature/next-task` 应替换为具体任务名，预检必须返回 `TASK_PREFLIGHT_OK`。已有任务分支先核对工作区和基线，不覆盖未提交内容。后续正常开发继承最新主线；第一版标签始终保持原样。

## 回读与恢复边界

查看第一版身份、文件及后续变化：

```powershell
git show --no-patch screen-v1.0.0
git rev-parse 'screen-v1.0.0^{commit}'
git show screen-v1.0.0:PROJECT_STATUS.md
git diff screen-v1.0.0 origin/main -- web/src/modules/large-screen
```

如需单独回读完整源码，在新的空目录旁克隆固定版本，不重置现有开发目录：

```powershell
git clone --branch screen-v1.0.0 https://github.com/edg127117/building-energy-carbon-platform.git building-energy-carbon-screen-v1
```

标签克隆处于分离 HEAD，适合回读与重建；需要恢复性开发时另建任务分支并按仓库流程交付，不改写主线历史。已有更高版本数据库不能直接配旧程序运行。环境恢复需先核对目标、保全新增数据、校验版本兼容性并执行独立备份恢复验证，遵循[部署与恢复约束](../development/repository-guardrails.md)，本次未执行运行环境回退或恢复演练。

## 验证记录入口

本次归档 PR 记录整合后的前端定向测试、完整测试、lint、类型检查、构建及文档检查；GitHub CI 状态和最终提交以该 PR 为准。19080 既有浏览器性能记录属于来源版本的历史证据，本次不重新进行现场或性能验收。GitHub 标签及 Release 保存最终版本身份；后续状态写入当前项目状态文档，本归档说明发布后保持冻结。
