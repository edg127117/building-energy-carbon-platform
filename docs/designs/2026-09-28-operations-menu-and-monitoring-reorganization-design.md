# 智慧运维菜单重构、动静分离与宏观运行总览设计方案

## 1. 状态与决策边界

- **状态**：用户已确认设计方向与架构边界；本文为正式设计文档，指导后续数据库菜单迁移、前端路由装配及页面重构实现，不代表相关改造已在生产代码中完成。
- **工程规则与状态依据**：长期约束遵循 [AGENTS.md](../../AGENTS.md) 与 [前端边界](../../web/AGENTS.md)；稳定架构见 [PROJECT_GUIDE.md](../../PROJECT_GUIDE.md)；当前能力与验收状态以 [PROJECT_STATUS.md](../../PROJECT_STATUS.md) 为准；第二阶段三系统公共骨架基线见 [第二阶段前端可视化实施计划](frontend-visualization-phase-two-implementation-plan.md)。
- **核心设计目标**：
  1. **解决菜单语义错位与职责混淆**：消除当前“实时监测 -> 设备实时监测”仅展示空调设备列表、“设备台账”混杂空调与电表、以及在静态设备台账抽屉中查看电表实时走势图的职责错位。
  2. **严格落实“动静分离”**：`设备管理 (/operations/devices/*)` 只承载静态资产档案、技术参数、协议身份与测点配置；`综合总览 (/operations/overview/*)` 与 `实时监控 (/operations/realtime/*)` 承载动态运行状态、实时读数、达标评价与时序走势图。
  3. **建立“大小监控分层”与非 3D 宏观看板**：在与独立 1920×1080 孪生大屏（`/monitor/*`）严格区分的前提下，于智慧运维平台落地 `综合总览 -> 运行总览 (/operations/overview/running)`，采用图文均衡、左右互补的 **F 型三段式布局**（顶部 4 张微图表与环形饼图 KPI 卡 + 中部单行多子系统胶囊切换栏 + 底部左栏“空间/回路累计投入与实时强度柱折混合图”与右栏“单设备实时运行与设定温度达标巡检列表” + 按需唤起单设备走势抽屉），既消除左右两栏重复展示设备状态计数的冗余感，又支持平滑切换暖通空调、供配电表计、冷热源、照明插座与可再生能源等建筑子系统。
- **非目标与硬边界**：
  - 不涉及任何远程控制、开关机指令下发、温度设定下发或自动化控制策略；
  - 不在前端浏览器重算后端权威能耗、折标、碳排或能效指标，不生成随机业务数据或虚假历史曲线；
  - 不修改已锁定的历史 Flyway 迁移脚本（`V58`、`V59`、`V64`），所有菜单调整通过新增增量迁移脚本（`V65`）实现；
  - 不改变 `孪生大屏系统 (/monitor/*)` 的独立画布与三维场景预留定位。

---

## 2. 既有骨架回看与防返工核对

为避免与前期已合入主线的三系统骨架、菜单治理及大金/电表组件产生冲突或返工，本文对现有代码基线进行了逐项回看与对齐：

| 既有实现与文档位置 | 当前主线（`origin/main`）事实 | 造成当前体验问题的原因 | 本方案防返工对齐策略 |
|---|---|---|---|
| [第二阶段前端实施计划 §15.4](frontend-visualization-phase-two-implementation-plan.md#L717-L747) 与 [`catalog.ts`](../../web/src/app/navigation/catalog.ts) | 前端注册表已预留完整规范路径：<br/>- `/operations/overview/running`<br/>- `/operations/realtime/hvac`、`power`、`lighting`、`renewable`<br/>- `/operations/devices/businessDevices`、`pendingDevices`、`meters` | 前端路由骨架虽已按“综合总览 + 分系统实时监控 + 业务设备/监测采集设备”设计，但数据库种子与页面绑定未完全跟进，导致部分预留叶子仍停留在 `PendingPage.vue` 或未在数据库授权树中启用 | **100% 复用 [`catalog.ts`](../../web/src/app/navigation/catalog.ts) 既有规范路径**，不另起炉灶发明新路径（从而完全兼容 [`catalog.test.ts`](../../web/src/app/navigation/catalog.test.ts) 与 [`router.test.ts`](../../web/src/app/router/router.test.ts) 的 49 项页面契约） |
| [`V59__mysql_retire_legacy_hvac_and_govern_menus.sql`](../../src/env/init/V59__mysql_retire_legacy_hvac_and_govern_menus.sql) 与 [`LegacyHvacRetirementAndMenuGovernanceContractTest.java`](../../src/test/java/com/platform/config/LegacyHvacRetirementAndMenuGovernanceContractTest.java) | `V59` 将菜单收口为三级树，但智慧运维下仅插入了：<br/>- `(310, '实时监测', '/operations/realtime')`<br/>- `(311, '设备实时监测', '/operations/realtime/hvac')`<br/>- `(230, '设备管理', '/operations/devices')`<br/>- `(252, '设备台账', '/operations/devices/businessDevices')`<br/>- `(254, '待接入设备', '/operations/devices/pendingDevices')` | 1. 数据库 `menu_name` 会覆盖前端默认 `titleKey`，导致 `/operations/realtime/hvac` 被显示为“设备实时监测”，让用户误以为实时监测只是空调设备列表；<br/>2. `/operations/overview/running`（运行总览）、`/operations/realtime/power`（电力监控）、`/operations/devices/meters`（监测采集设备）未写入 `sys_menu` | **绝不原地修改 `V58` / `V59`**；新增 `V65` 增量迁移脚本修正 `menu_name` 并补齐 `运行总览`、`电力监控`、`监测采集设备` 菜单节点与存量角色授权继承 |
| [`EquipmentPointPage.vue`](../../web/src/modules/asset-management/pages/EquipmentPointPage.vue) 与 [`MeterRealtimeBoard.vue`](../../web/src/modules/asset-management/components/meter/MeterRealtimeBoard.vue) | 1. `/operations/devices/businessDevices` 未按设备属性过滤，空调内机（`IDU`）、外机（`ODU`）与单/三相电表混在同一表格；<br/>2. 电表行点击“查看电表测点”直接在静态台账抽屉内渲染 `<MeterRealtimeBoard>`（含实时看板与连续时序走势图）；<br/>3. 电表详情的“技术参数”页签仍展示暖通专属的“额定冷热量 / 设计能效比 COP” | 电表的动态监控与走势图被塞进了静态设备台账，而真正的 `/operations/realtime/power`（电力监控）与 `/operations/devices/meters`（监测采集设备）反而闲置为“待建设” | 1. 将 `MeterRealtimeBoard` 及走势图能力迁入 `/operations/realtime/power`（电力监控）及运行总览抽屉；<br/>2. `EquipmentPointPage.vue` 通过模式参数同时支撑 `用能设备台账` 与 `监测采集设备`，实现静态分类隔离与按类展示技术参数 |
| [`DaikinMonitoringPanel.vue`](../../web/src/modules/dashboard/components/DaikinMonitoringPanel.vue) 与 [`DaikinDeviceDetail.vue`](../../web/src/modules/dashboard/components/DaikinDeviceDetail.vue) | 已实现快捷状态胶囊筛选、`按外机系统分组 / 按房间空间分组`、`卡片视图 / 列表视图`、室内温度与设定温度卡片水合，以及右侧抽屉（含 90 天温度曲线、状态变化、异常记录、厂家统计与 V62 平台自算开机时长） | 已有完备的单系统/单设备工作台与详情抽屉，但缺少面向楼宇管理者的“空间累计投入与实时强度柱折混合图 + 设定温度达标巡检列表”宏观视图 | **完整保留并复用 `DaikinMonitoringPanel` 与 `DaikinDeviceDetail` 抽屉**，在其之上封装宏观子系统空间柱折图与达标巡检列表，避免重复开发设备详情抽屉 |
| [`check-foundation.mjs`](../../web/scripts/check-foundation.mjs) 与 [`foundation-rules.test.ts`](../../web/src/app/foundation-rules.test.ts) | 静态门禁强制检查：`MODULE_PUBLIC_ENTRY`（跨模块仅导入 `public.ts`）、`HARDCODED_COPY`（禁止在非语言包写中文）、`HARDCODED_COLOR` / `HARDCODED_SIZE` / `STYLE_TOKEN_REQUIRED` / `SCOPED_STYLE_REQUIRED` | 若跨模块直接引用内部组件或在组件内硬编码中文/色值，会导致构建门禁失败 | 所有跨模块组件复用（如 `dashboard` 与 `asset-management` 协作）严格经由各自 `public.ts` 导出；所有新增文案写入 `locales`，样式统一使用 `--bec-*` Token |

---

## 3. 既有约束处理关系

按 [AGENTS.md §6](../../AGENTS.md) 要求，本方案对既有设计约束的处理明确标识如下：

| 既有约束或状态 | 处理方式 | 本方案明确结果 |
|---|---|---|
| 三工作区（孪生大屏、智慧运维、能碳配置）与两类外壳（`MonitorLayout`、`OfficeLayout`） | **保留** | 智慧运维宏观看板运行于 `OfficeLayout` 浅色管理外壳内，不混用 `MonitorLayout` 的 1920×1080 等比缩放与三维场景底座 |
| [`catalog.ts`](../../web/src/app/navigation/catalog.ts) 已注册的 49 个规范页面路径与三级菜单校验（`authorizedPages`） | **保留** | 不新增或删除 `catalog.ts` 中的规范路径，保持 `workspace M -> group M -> leaf C` 严格层级校验 |
| `V59` 中将 `/operations/realtime` 命名为“实时监测”、将 `/operations/realtime/hvac` 命名为“设备实时监测” | **替代（经新迁移脚本更新）** | 通过 `V65` 迁移统一为“实时监控”与“暖通空调监控”，消除“设备监控只看空调”的语义歧义 |
| `V59` 中仅启用 `/operations/devices/businessDevices`（设备台账）并混放空调与电表 | **迁移** | 启用 `/operations/devices/meters`（监测采集设备），将 `/operations/devices/businessDevices` 明确定位为“用能设备台账”，实现用能业务设备与计量采集设备分账管理 |
| 电表实时看板与走势图（`MeterRealtimeBoard.vue`、`MeterRealtimeTrendChart.vue`）内嵌于设备台账抽屉 | **迁移** | 迁入 `实时监控 -> 电力监控 (/operations/realtime/power)` 及 `运行总览` 设备抽屉；静态台账抽屉的“测点”页签回归静态测点配置清单，并提供跳转实时监控入口 |
| `综合总览 -> 运行总览 (/operations/overview/running)` 处于 `PendingPage.vue` 占位状态 | **替代（落地实现）** | 建设非 3D 宏观运行总览看板，承接全楼能碳与分项环形饼图、多子系统切换、空间/回路累计投入与实时强度柱折混合图及实时达标巡检列表 |
| 能源计量、折标、碳排及冷站 EERp 的权威计算与质量门禁在后端执行 | **保留** | 宏观看板的能碳与分项卡片仅消费后端已发布接口；后端未配置或无有效数据时诚实展示“暂无已封账/已核定数据”或引导状态，绝不在前端捏造指标 |

---

## 4. 智慧运维平台菜单调整与权限映射清单

### 4.1 智慧运维平台（`/operations`）目标菜单树

以下为调整后的 `智慧运维平台` 完整菜单清单。其中**粗体**为本次激活、更名或重构职责的核心节点：

| 一级分组（`M`） | 规范分组路径 | 二级叶子名称（`C`） | 规范叶子路径 | 本次处理与页面职责边界 |
|---|---|---|---|---|
| **综合总览** | `/operations/overview` | **运行总览** | `/operations/overview/running` | **【本次激活并实现】** 面向楼宇管理者的非 3D 宏观监控看板：顶部 4 张微图表与环形饼图能碳/态势卡 + 中部单行 5 子系统胶囊切换栏 + 底部左栏空间/回路累计投入与实时强度柱折混合图 + 右栏单设备实时达标巡检列表 |
| **实时监控** | `/operations/realtime` | **暖通空调监控** | `/operations/realtime/hvac` | **【更名并增强】** 数据库名称由“设备实时监测”恢复为“暖通空调监控”；保留 PR #112 的大金内外机系统/房间分组、卡片/列表视图与详情抽屉，顶部增补可折叠的空间运行汇总与设定温度达标概览条，并保留冷热源（继承冷站）页签 |
| **实时监控** | `/operations/realtime` | **电力监控** | `/operations/realtime/power` | **【本次激活并迁入实现】** 承接全楼单相/三相电表的动态监控列表、分相电参量实时看板（`SinglePhaseMeterBoard` / `ThreePhaseMeterBoard`）、连续时序走势图（`MeterRealtimeTrendChart`）与原始读数表 |
| 实时监控 | `/operations/realtime` | 照明插座监控 | `/operations/realtime/lighting` | **【保留预留】** 已在 `catalog.ts` 注册，未接入真实照明回路前保持统一“待建设”占位 |
| 实时监控 | `/operations/realtime` | 可再生能源监控 | `/operations/realtime/renewable` | **【保留预留】** 已在 `catalog.ts` 注册，未接入光伏/储能设备前保持统一“待建设”占位 |
| 能耗管理 | `/operations/energy` | 分项能耗、分区能耗、趋势对比、基准与定额分析、能耗诊断 | `/operations/energy/*` | **【保留既有】** `趋势对比 (/operations/energy/trend)` 保持已迁入的历史趋势页面，其余未完成叶子保持占位；与 `运行总览` 顶部能碳卡片形成下钻闭环 |
| 碳排放管理 | `/operations/carbon` | 排放总览、排放明细、趋势对比、减排管理、碳资产管理 | `/operations/carbon/*` | **【保留既有】** 与 `运行总览` 顶部碳排卡片形成跳转闭环 |
| **设备管理** | `/operations/devices` | **用能设备台账** | `/operations/devices/businessDevices` | **【职责收纯与分类】** 数据库名称与文案统一为“用能设备台账”（对应原业务设备）；仅管理空调内机、空调外机、冷机、水泵、冷却塔等**用能业务设备**的静态档案、测点定义、连接身份与技术参数；提供一键跳转实时监控入口 |
| **设备管理** | `/operations/devices` | **监测采集设备** | `/operations/devices/meters` | **【本次激活并实现】** 独立管理单相电表、三相电表及后续水表、气表、热量表等**监测采集表计**的静态档案、静态测点配置与通讯身份；移除不适用于电表的“额定冷热量/设计COP”参数；提供一键跳转“电力监控”查看实时走势入口 |
| 设备管理 | `/operations/devices` | 待接入设备 | `/operations/devices/pendingDevices` | **【保留既有】** 统一承载报文发现设备与大金厂家目录同步、类型化绑定及温度模板补齐申请 |
| 报警管理 | `/operations/alarms` | 实时报警、历史报警 | `/operations/alarms/liveAlarms`、`historyAlarms` | **【保留既有】** 承载当前活动异常与历史已恢复异常查询 |
| 运维管理 | `/operations/maintenance` | 维保计划、工单管理、故障记录 | `/operations/maintenance/*` | **【保留预留】** 保持统一“待建设”占位 |
| 报表中心 | `/operations/reports` | 能耗报表、碳排报表 | `/operations/reports/*` | **【保留预留】** 保持统一“待建设”占位 |

### 4.2 数据库增量迁移与权限平滑继承清单（`V65`）

1. **禁止改动历史迁移**：`V58`、`V59` 与 `V64` 已合入主线并受契约测试锁定，任何字节级修改均会破坏 Flyway 校验和与契约测试。
2. **新增 `V65__mysql_reorganize_operations_monitoring_and_device_menus.sql`**：
   - **排序与目录补齐**：
     - 插入/更新一级分组 `(305, 300, '综合总览', 'M', '/operations/overview', NULL, 'dashboard', 1, 1, 1)`；
     - 插入/更新二级叶子 `(306, 305, '运行总览', 'C', '/operations/overview/running', NULL, 'data-board', 1, 1, 1)`；
     - 更新一级分组 `id = 310`（`/operations/realtime`）：`menu_name = '实时监控'`，`sort_order = 2`；
     - 更新二级叶子 `id = 311`（`/operations/realtime/hvac`）：`menu_name = '暖通空调监控'`，`sort_order = 1`；
     - 插入/更新二级叶子 `(312, 310, '电力监控', 'C', '/operations/realtime/power', NULL, 'trend', 1, 1, 2)`；
     - 更新一级分组 `id = 230`（`/operations/devices`）：`menu_name = '设备管理'`，`sort_order = 5`；
     - 更新二级叶子 `id = 252`（`/operations/devices/businessDevices`）：`menu_name = '用能设备台账'`，`sort_order = 1`；
     - 插入/更新二级叶子 `(256, 230, '监测采集设备', 'C', '/operations/devices/meters', NULL, 'tool', 1, 1, 2)`；
     - 更新二级叶子 `id = 254`（`/operations/devices/pendingDevices`）：`menu_name = '待接入设备'`，`sort_order = 3`。
   - **角色菜单授权平滑继承（`sys_role_menu`）**：
     - 凡已拥有 `311`（`/operations/realtime/hvac`）授权的角色，自动通过 `INSERT IGNORE INTO sys_role_menu` 授予 `306`（`/operations/overview/running`）与 `312`（`/operations/realtime/power`），确保原有运维监控角色升级后立即可见宏观总览与电力监控；
     - 凡已拥有 `252`（`/operations/devices/businessDevices`）授权的角色，自动授予 `256`（`/operations/devices/meters`）与 `312`（`/operations/realtime/power`），确保原先在设备台账中查看电表档案和走势图的用户不会因菜单拆分而丢失访问权限。
3. **后端接口菜单守卫同步**：
   - [`DaikinMonitoringQueryService.java`](../../src/main/java/com/platform/iot/daikin/monitoring/query/DaikinMonitoringQueryService.java)：将 `HVAC_MENUS` 扩展为 `Set.of("/operations/realtime/hvac", "/operations/overview/running")`，使拥有 `运行总览` 或 `暖通空调监控` 任一菜单授权的用户均可按其已授权建筑范围读取空间运行汇总与实时状态；
   - [`AssetManagementController.java`](../../src/main/java/com/platform/hvac/asset/api/AssetManagementController.java)：保持后端建筑范围与角色校验边界不变。

---

## 5. 页面布局与组件复用详细设计

### 5.1 `综合总览 -> 运行总览 (/operations/overview/running)`：非 3D 宏观监控看板（V4 定稿方案）

#### （1）与孪生大屏（`/monitor/monitoring`）的严格区分
- **孪生大屏（`/monitor/monitoring`）**：独立于运维平台和配置平台，使用 `MonitorLayout` 1920×1080 等比缩放画布，中央预留建筑三维模型，左右两侧为半透明悬浮面板。
- **智慧运维 `运行总览 (/operations/overview/running)`**：位于智慧运维平台内部，使用 `OfficeLayout` 标准浅色企业管理外壳（`office-light`），**不放三维模型，不采用“中间三维 + 左右环绕”布局**，而是面向日常办公桌面的高可读性、图文均衡、左右维度互补的 **F 型三段式布局**。

#### （2）视觉减压与左右互补原则（消除重复感与文字堆砌）
1. **顶部卡片真实数据与未配置引导边界**：
   - 卡片 1（`建筑综合能耗概览`）与卡片 2（`建筑碳排放概览`）：在当前建筑尚未完成总表/分项计量边界核定与排放因子配置前，严格遵守 `AGENTS.md`“禁止生成随机业务数据或虚假历史”的硬约束，**不展示虚假数值与假迷你柱图/假面积图**，而是呈现清晰的 **“待配置计量边界 / 待配置排放因子”引导状态**（显示 `— kWh` / `— tCO₂e`、前置配置说明与一键跳转配置/分项分析入口）；
   - 卡片 3（`分项用能设备结构占比`）与卡片 4（`全局设备运行态势`）：采用“左侧 SVG 环形饼图（Donut Chart）+ 右侧 2×2 精简图例”，基于当前建筑真实已接入设备台账与实时运行状态计算占比与在线健康率；
2. **第三段左 5 : 右 7 维度互补（不重复展示运行/异常台数）**：
   - **左栏（5/12）聚焦“空间/回路的累计投入量与实时负荷/均温强度”**：顶部展示 3 个子系统宏观累计指标块，下方采用 **“竖向柱状图 + 实测均温/回路覆盖指标”柱折混合视图**（左轴竖柱代表各空间的真实观测累计开机时长或区域表计配置数；顶部胶囊代表各空间的实测平均室温或三相主回路占比），点击任意空间柱体可联动过滤右侧列表；
   - **右栏（7/12）聚焦“单设备的实时运行状态与目标达标巡检”**：展示当前子系统下每台设备的实时工况、设定温度 vs 实测室温双标尺对比条（或电表相数/分组状态）与状态评价标签；
3. **单设备复杂时序曲线一律“按需下钻（Drawer）”**：不在宏观首屏平铺多张单设备折线走势图；点击右侧巡检列表中的任意设备行，才从右侧滑出详情抽屉查看该设备的 90 天温度曲线或电表分相趋势曲线。

#### （3）F 型三段式版面结构（V4 定稿）

```text
+-------------------------------------------------------------------------------------------------------+
| 页面标题栏：综合运行总览                       [建筑选择: 1号实验楼 v]  [状态刷新时间: 14:30] [刷新]  |
+-------------------------------------------------------------------------------------------------------+
| 【第一段：全楼能碳（待配置引导）、分项占比与全局运行态势总览条（4 等宽卡片）】                        |
| +-------------------------+ +-------------------------+ +-------------------------+ +---------------+ |
| | 1. 建筑综合能耗概览     | | 2. 建筑碳排放概览       | | 3. 分项用能设备结构占比 | | 4. 全局态势   | |
| | [待配置计量边界]        | | [待配置排放因子]        | | (环形图) | 暖通 / 表计  | | (环形图) 88%  | |
| | — kWh (需先核定计量边界)| | — tCO₂e (需先配置因子)  | |  已接入  | 冷源 / 其他  | | 在运/温差/离线| |
| | [前往计量边界/分项能耗] | | [前往排放因子/排放总览] | |          | 2×2 色彩图例 | | [去暖通监控->]| |
| +-------------------------+ +-------------------------+ +-------------------------+ +---------------+ |
+-------------------------------------------------------------------------------------------------------+
| 【第二段：多子系统胶囊切换导航栏（单行轻量切换，支持后续子系统平滑接入）】                            |
| [ ● 暖通空调末端 34 ]  [ ○ 供配电与表计 18 ]  [ ○ 冷热源机房 4 ]  [ ○ 照明与插座 ]  [ ○ 可再生能源 ]  |
+-------------------------------------------------------------------------------------------------------+
| 【第三段：子系统空间投入/强度分析与单设备达标巡检主画布（左 5 : 右 7 维度互补分栏）】                 |
| +---------------------------------------------+ +---------------------------------------------------+ |
| | [左栏 5/12] 空间/回路累计投入与实时强度分析 | | [右栏 7/12] 单设备实时运行与达标巡检列表          | |
| | +------------+ +------------+ +-----------+ | | 筛选：[全部] [运行中] [温差偏大] [异常] [已关机]  | |
| | |累计开机时长| |单机日均时长| |在运平均室温| | | +-----------------------------------------------+ | |
| | | 186.5 台·h | | 6.2 h/台   | |  24.9 °C  | | | | 空间  | 设备名称 | 模式 | 实测 vs 设定 | 评价 | | |
| | +------------+ +------------+ +-----------+ | | | 101室 | 空调#01  | 制冷 | 24.5°C/24.0°C| 达标 | | |
| | (柱折混合图：点击空间柱体联动过滤右侧列表)  | | | 103室 | 空调#01  | 制冷 | 28.5°C/24.0°C| +4.5°| | |
| | 左轴(柱)：各空间累计开机时长(h)/在线设备数  | | | 201室 | 空调#02  | 制冷 | 25.2°C/25.0°C| 达标 | | |
| | 右轴(线)：各空间实测平均室温(°C) vs 设定基准| | | 302室 | 空调#01  | 送风 | 26.8°C/ —    | 过期 | | |
| |   |   _--_   (折线：空间均温/实时功率)      | | | 105室 | 空调#01  | 关机 | 26.8°C/26.0°C| 关机 | | |
| |   |  |    |  |                              | | +-----------------------------------------------+ | |
| |  101 103  201 302 105                       | | (点击任意行 -> 右侧按需滑出单设备曲线与详情抽屉)  | |
| +---------------------------------------------+ +---------------------------------------------------+ |
+-------------------------------------------------------------------------------------------------------+
```

#### （4）多子系统平滑扩展机制（左栏“累计量+实时强度”与右栏“单设备巡检”统一容器）
第三段主画布采用**“统一栅格容器 + 子系统视图适配器”**设计，切换第二段子系统胶囊时，左栏始终保持“顶部 3 个宏观累计指标块 + 下方空间/回路柱折混合图”结构，右栏切换为对应子系统的单设备巡检列：

| 子系统页签 | 左栏顶部 3 汇总块 | 左栏：空间/回路柱折混合图（竖柱 + 折线） | 右栏：单设备实时运行与达标巡检列表核心列 | 点击行唤起的右侧抽屉 |
|---|---|---|---|---|
| **暖通空调末端**（当前已具备数据） | `今日累计开机总时长 (台·h)`、`单机日均开机时长 (h/台)`、`在运空间平均室温 (°C)` | **竖柱（左轴）**：各空间累计开机时长 `h`（或空间在运内机数）<br/>**折线（右轴）**：各空间平均实测室温 `°C` vs 平均设定温度基准线 | `所属空间`、`设备名称（内/外机）`、`运行模式与风速`、**`实测室温 vs 设定温度（双标尺进度条）`**、`状态评价（室温达标 / 温差较大 / 读数过期 / 已关机）`、`操作（查看曲线）` | 复用 [`DaikinDeviceDetail.vue`](../../web/src/modules/dashboard/components/DaikinDeviceDetail.vue)（温度曲线 + 开机时长 + 异常） |
| **供配电与表计**（当前已具备电表数据） | `在线监测电表总数 (块)`、`三相 3P / 单相 1P 分布`、`已绑定空间/回路覆盖数`（若存在聚合电能则展示分项电耗/功率） | **竖柱（左轴）**：各安装空间/系统分组下的监测表计数量（或回路累计电耗 `kWh`）<br/>**折线（右轴）**：各空间三相主回路表占比 `%`（或回路实时有功功率 `kW`） | `安装区域`、`电表名称（含编码）`、`相数规格（三相 3P / 单相 1P）`、`所属系统分组`、`状态评价`、`操作（查看走势）` | 复用 `<MeterRealtimeBoard>` 抽屉（单/三相实时看板 + 连续时序走势图） |
| **冷热源机房**（继承冷站或后续冷站接入） | `冷站系统设备总数`、`主机/水泵/冷却塔分类数`、`当前能效监测状态` | **竖柱（左轴）**：各子系统（冷机/冷冻泵/冷却泵/冷却塔）台数分布<br/>**折线（右轴）**：额定功率/冷量配置分布 | `设备名称`、`设备类型`、`所属空间/机房`、`额定功率 / 额定冷热量`、`操作（前往冷站监测）` | 跳转或展开冷站监测页签 |
| **照明与插座 / 可再生能源**（后续扩展） | `照明总电耗 / 光伏总发电量`、`回路开启率 / 自发自用率`、`当前总功率 (kW)` | **竖柱（左轴）**：各楼层照明电耗 / 各阵列今日发电量 `kWh`<br/>**折线（右轴）**：各楼层亮灯率 `%` / 各逆变器实时出力率 `%` | 回路开关状态、开启率、非工作时段亮灯提醒 / 逆变器实时发电功率 | 后续接入协议后原位替换，当前在页签内提供清晰的“暂未接入该子系统设备，前往待接入设备配置”引导 |

#### （5）设定温度达标评价口径边界
- 遵守 [AGENTS.md §3](../../AGENTS.md)“软件不得自行猜测专业规则”的硬约束：
  - 列表直接展示实测 `室内温度 (roomTemp)` 与 `设定温度 (temperature)` 及其温差值（$\Delta T = \text{roomTemp} - \text{setTemp}$）；
  - 当设备处于开机运行（`power === 'ON'`）且同时具备有效 `roomTemp` 与 `setTemp` 时，展示双标尺温度对比条；若温差绝对值超过前端展示提醒参考值（默认 $|\Delta T| \ge 2.0^\circ\text{C}$）或后端返回告警状态，仅以中性辅助标签提示“温差较大（+X.X°C）”供运维巡检关注，**不将其伪造为后端权威能效不合格结论或硬件故障**。

---

### 5.2 `实时监控 -> 暖通空调监控 (/operations/realtime/hvac)`：增补空间汇总与达标视图

在保留 PR #112 已实现的 [`DaikinMonitoringPanel.vue`](../../web/src/modules/dashboard/components/DaikinMonitoringPanel.vue) 全部能力（建筑/类型/空间/开关/异常筛选、快捷状态胶囊、`按外机系统分组 / 按房间空间分组`、`卡片视图 / 列表视图`、右侧 `DaikinDeviceDetail` 抽屉）基础上：
1. **顶部集成可折叠的“空间运行与设定温度达标概览”条**：
   - 让直接进入 `暖通空调监控` 的运维人员无需切回 `运行总览`，也能直观看到各房间开机/关机/温差偏大分布与均温对比，并支持点击某个房间芯片直接过滤下方的空调卡片或列表；
2. **列表视图（`TABLE`）强化设定温度达标对比列**：
   - 在列表视图中并列展示 `室内温度`、`设定温度` 与 `温差状态`，支持按“温差偏大 / 存在异常 / 运行中”快速筛选巡检；
3. **保留“继承冷站”页签**：
   - [`HvacMonitoringPage.vue`](../../web/src/modules/dashboard/pages/HvacMonitoringPage.vue) 保持 `大金空调监测` 与 `冷站监测` 双页签按需挂载，互不干扰。

---

### 5.3 `实时监控 -> 电力监控 (/operations/realtime/power)`：电表实时运行与走势监控

新建 `web/src/modules/asset-management/pages/PowerMonitoringPage.vue`（经 `asset-management/public.ts` 暴露路由，装配至 `/operations/realtime/power`），将原先藏在“设备台账”抽屉里的电表动态监控能力正式扶正为独立的实时监控模块：

1. **顶部筛选与状态概览**：
   - 支持按 `建筑`、`所属空间`、`所属系统分组`、`表计相数（全部 / 单相电表 1P / 三相电表 3P）`、`关键字` 筛选；
   - 顶部轻量汇总条显示：`监测电表总数`、`三相电表数`、`单相电表数`、`当前选中电表状态`。
2. **电表实时监测列表**：
   - 仅加载并展示监测采集电表（基于 [`isMeterEquipment`](../../web/src/modules/asset-management/components/meter/meter-display.ts) 及设备类型过滤）；
   - 列表展示：`电表名称`、`设备编码`、`分相类型标签（单相/三相）`、`安装位置（空间/建筑）`、`计量系统分组`、`操作（查看实时看板与走势图 / 查看静态档案）`。
3. **右侧实时监控与走势抽屉（复用既有成熟组件）**：
   - 点击列表中的“查看实时看板与走势图”（或携带 `?equipmentId=xxx` 深链接进入页面时自动打开），滑出宽幅监控抽屉，直接渲染 [`MeterRealtimeBoard.vue`](../../web/src/modules/asset-management/components/meter/MeterRealtimeBoard.vue)：
     - 单相电表自动渲染 [`SinglePhaseMeterBoard.vue`](../../web/src/modules/asset-management/components/meter/SinglePhaseMeterBoard.vue)；
     - 三相电表自动渲染 [`ThreePhaseMeterBoard.vue`](../../web/src/modules/asset-management/components/meter/ThreePhaseMeterBoard.vue)；
     - 下方完整保留 [`MeterRealtimeTrendChart.vue`](../../web/src/modules/asset-management/components/meter/MeterRealtimeTrendChart.vue)（1 分钟轮询、最长 90 天历史时序分桶查询、全屏放大查看）与 [`MeterPointReadingTable.vue`](../../web/src/modules/asset-management/components/meter/MeterPointReadingTable.vue)；
   - 抽屉头部提供“查看静态台账档案”辅助链接，可一键跳转至 `/operations/devices/meters?equipmentId=xxx`。

---

### 5.4 `设备管理`：用能设备台账与监测采集设备彻底动静分离

遵循 [第二阶段前端实施计划 §15.4](frontend-visualization-phase-two-implementation-plan.md#L745)“设备分类共用列表与详情组件，不按每种仪表复制整套页面”的原则，扩展 [`EquipmentPointPage.vue`](../../web/src/modules/asset-management/pages/EquipmentPointPage.vue) 支持 `ledgerCategory: 'BUSINESS' | 'METER'` 属性，分别服务两个菜单路由：

#### （1）`设备管理 -> 用能设备台账 (/operations/devices/businessDevices)`（`ledgerCategory = 'BUSINESS'`）
1. **设备范围与顶部分类页签**：
   - 仅展示非表计类的**用能业务设备**（`!isMeterEquipment(row)`）；
   - 列表上方提供业务设备分类快捷页签：`全部用能设备` | `暖通空调内机 (IDU)` | `暖通空调外机 (ODU)` | `冷热源及水泵风机` | `其他用能设备`，后期接入新设备类型时按分类自然归档，不再混成一团。
2. **纯静态详情抽屉**：
   - **设备档案**：基本属性（名称、编码、类型、产品模板、厂商）与安装位置（建筑、空间、系统分组）；
   - **测点配置**：展示静态 `biz_data_point` 测点配置表格（测点名称、测点编码、单位、是否必填、是否参与计算、编辑/删除测点操作）；
   - **连接与身份**：协议模板与设备身份标识（`DAIKIN_UNIT`、网关标识等）；
   - **技术参数**：展示用能设备专属技术参数（`额定冷热量`、`额定功率`、`设计能效比 COP`）；
   - **动静联动入口**：表格操作列与抽屉头部提供 **“去实时监控查看运行”** 按钮，点击携带 `buildingId` 与 `equipmentId` 跳转至 `/operations/realtime/hvac`。

#### （2）`设备管理 -> 监测采集设备 (/operations/devices/meters)`（`ledgerCategory = 'METER'`）
1. **设备范围与顶部分类页签**：
   - 仅展示**监测采集表计设备**（`isMeterEquipment(row)`）；
   - 列表上方提供表计分类快捷页签：`全部监测表计` | `单相电表 (1P)` | `三相电表 (3P)`（后续接入水表、燃气表、冷热量表时直接扩展子分类页签）。
2. **纯静态详情抽屉（移除内嵌实时走势图与无关暖通参数）**：
   - **设备档案**：表计基本信息、产品模板（如 `INDOOR_UNIT_METER_1039`、`OUTDOOR_UNIT_METER_339`）与安装位置；
   - **测点配置（关键修正）**：**不再在此处渲染 `<MeterRealtimeBoard>` 动态走势图**，而是与用能设备一样展示静态 `biz_data_point` 测点定义表（支持管理员查看/编辑电压、电流、有功功率、正向有功电能等测点的范围、计算标记与启停状态），真正恢复静态台账的测点管理职责；
   - **连接与身份**：展示报文协议编码（如 `device/raw/energy/up`）、设备 SN 身份状态；
   - **技术参数（关键修正）**：隐藏仅适用于暖通主机的“额定冷热量”与“设计能效比 COP”字段，避免在电表档案上出现无意义的暖通参数；
   - **动静联动入口**：表格操作列与抽屉头部提供醒目的 **“去电力监控查看走势”** 按钮，点击携带 `buildingId` 与 `equipmentId` 跳转至 `/operations/realtime/power?buildingId=...&equipmentId=...` 并自动展开该电表的实时监控与走势抽屉。

---

## 6. 涉及文件与模块边界清单

| 层级 / 模块 | 涉及文件路径 | 变更性质与职责说明 |
|---|---|---|
| 数据库迁移 | `src/env/init/V65__mysql_reorganize_operations_monitoring_and_device_menus.sql` | 新增增量迁移：修正 `实时监控`、`暖通空调监控`、`用能设备台账` 名称，启用 `综合总览 -> 运行总览`、`实时监控 -> 电力监控`、`设备管理 -> 监测采集设备`，并迁移角色菜单授权 |
| 后端服务 | [`DaikinMonitoringQueryService.java`](../../src/main/java/com/platform/iot/daikin/monitoring/query/DaikinMonitoringQueryService.java) 及对应单测 | 允许 `/operations/overview/running` 与 `/operations/realtime/hvac` 共同通过菜单权限守卫访问空间运行汇总数据 |
| 前端全局导航与路由 | [`web/src/app/router/index.ts`](../../web/src/app/router/index.ts)、[`web/src/locales/zh-CN/workspaces.ts`](../../web/src/locales/zh-CN/workspaces.ts) | 将 `/operations/overview/running`、`/operations/realtime/power`、`/operations/devices/meters` 绑定至实际业务页面组件；更新 `businessDevices` 默认文案为 `用能设备台账` |
| 资产与电表模块（`asset-management`） | [`EquipmentPointPage.vue`](../../web/src/modules/asset-management/pages/EquipmentPointPage.vue)、`PowerMonitoringPage.vue`、[`routes.ts`](../../web/src/modules/asset-management/routes.ts)、[`public.ts`](../../web/src/modules/asset-management/public.ts)、[`locales/zh-CN.ts`](../../web/src/modules/asset-management/locales/zh-CN.ts) | 1. `EquipmentPointPage.vue` 支持 `BUSINESS` / `METER` 双模式过滤、分类页签、静态测点表与跨页监控跳转；<br/>2. 新增 `PowerMonitoringPage.vue` 承载 `/operations/realtime/power`；<br/>3. 经 `public.ts` 导出电表监控组件与工具函数供 `dashboard` 宏观总览复用 |
| 监控与看板模块（`dashboard`） | `RunningOverviewPage.vue`、[`HvacMonitoringPage.vue`](../../web/src/modules/dashboard/pages/HvacMonitoringPage.vue)、[`DaikinMonitoringPanel.vue`](../../web/src/modules/dashboard/components/DaikinMonitoringPanel.vue)、[`routes.ts`](../../web/src/modules/dashboard/routes.ts)、[`locales/zh-CN.ts`](../../web/src/modules/dashboard/locales/zh-CN.ts) | 1. 新增 `RunningOverviewPage.vue` 实现 F 型三段式宏观运行总览（含顶部微图表与环形饼图 KPI 卡、5 子系统胶囊切换、左栏空间/回路累计投入与实时强度柱折混合图、右栏实时达标巡检列表与按需抽屉）；<br/>2. 在 `DaikinMonitoringPanel.vue` 顶部增补可折叠的空间运行与设定温度达标概览条 |

---

## 7. 分阶段实施顺序与验证矩阵

实施阶段严格遵循 [`iot-change-verification`](../../.agents/skills/iot-change-verification/SKILL.md) 规范：

1. **阶段一：数据库菜单迁移与后端权限守卫对齐**
   - 编写 `V65` 迁移脚本并增补迁移契约测试（确保不破坏 `LegacyHvacRetirementAndMenuGovernanceContractTest` 与 `FlywayMigrationConfigurationTest`）；
   - 更新 `DaikinMonitoringQueryService` 菜单列表并运行后端定向单测与 `.\mvnw.cmd test`。
2. **阶段二：设备管理动静分离与电力监控独立（`asset-management`）**
   - 重构 `EquipmentPointPage.vue` 区分 `用能设备台账 (/operations/devices/businessDevices)` 与 `监测采集设备 (/operations/devices/meters)`，恢复电表静态测点表并隐藏无关暖通参数；
   - 实现 `PowerMonitoringPage.vue (/operations/realtime/power)` 并接入 `MeterRealtimeBoard` 与深链接自动打开抽屉；
   - 补齐 `EquipmentPointPage.test.ts` 与 `PowerMonitoringPage.test.ts`。
3. **阶段三：宏观运行总览与暖通空间达标视图（`dashboard` + `app/router`）**
   - 实现 `RunningOverviewPage.vue`（顶部 4 张微图表/环形饼图卡 + 5 子系统胶囊切换 + 左 5/12 空间/回路累计投入与实时强度柱折混合图 + 右 7/12 单设备达标巡检列表 + 按需抽屉）；
   - 在 `DaikinMonitoringPanel.vue` 集成可折叠的空间运行汇总条与设定温度达标对比；
   - 在 `app/router/index.ts` 完成路由装配，运行前端定向 Vitest、全量 `npm run test:run`、`npm run lint`（含 `check-foundation.mjs` 架构门禁）、`npm run check` 与 `npm run build`。
