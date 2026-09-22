# 建筑能碳监测管理平台项目指南

## 1. 使用方式

本文件只描述稳定定位、架构、数据链路、职责和入口。开始任务时先读取 [`AGENTS.md`](AGENTS.md)，再按任务需要读取相关章节：

- 当前能力、阶段、风险和下一步：[`PROJECT_STATUS.md`](PROJECT_STATUS.md)；
- 具体实现：直接相关的代码、测试和专题文档；
- 监控大屏的内容、布局、交互、下钻及后续复用：[设计与复用规范](docs/designs/monitoring-screen-design-reference.md)；该文档区分独立原型与正式业务能力；
- Git、Hook、PR 和 CI：[`repository-guardrails.md`](docs/development/repository-guardrails.md)。

普通单点任务不要求完整读取本文件或 `PROJECT_STATUS.md`。历史计划只用于解释演进原因，不能代替当前代码和状态文档。

## 2. 产品定位与范围

本项目建设“建筑能碳监测管理平台”，围绕建筑、空间、系统、设备、测点和计量边界形成可追溯的能源与碳数据链，逐步支持：

- 冷热源系统；
- 空调及通风系统；
- 供配电系统；
- 环境监测系统。

产品阶段依次为数据监测与后台基础架构、可视化实现、虚拟数据测试及次级应用点完善；当前进度只在 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 维护。

平台范围以监测、预处理、建模、分析、诊断和展示为主。远程控制、命令下发、响应执行、策略生成与策略下发不属于当前版本。负荷预测、能源审计、运行模式管理及完整自动报告暂缓，启用前必须重新确认范围和输入条件。

GB/T 47474—2026 用于需求拆解和验收依据；只有相应条款完成软件实现、专业确认和必要的硬件/现场验证后，才能声明该条款已满足。

## 3. 与旧中央空调系统的关系

| 对象 | 定位 | 使用规则 |
|---|---|---|
| `building-energy-carbon-platform` | 当前产品仓库 | 所有新增开发、状态和交付均在此管理 |
| `iot-platform-demo` | 旧中央空调仓库 | 只通过 `upstream-hvac` 追溯历史，不继续承载新产品开发 |
| `baseline/hvac-before-energy-carbon-20260823` | 二次开发前代码冻结点 | 证明继承起点，不代表建筑能碳版本或现场验收完成 |
| 旧 HVAC 设计、计划和 PR | 历史证据 | 可解释已有实现，不是新平台需求或完成状态 |

继承代码中的 `hvac` 包、19 测点、公式、页面、脚本和制品名称可以继续运行，但应视为待复用或迁移的基础能力；未经新平台范围核对，不得推广为通用建筑能碳能力。

## 4. 稳定技术架构

平台采用 Spring Boot 单体后端与 Vue 前端。未经证据证明存在独立部署、扩缩容或故障隔离收益，不拆微服务或仓库。

大金只读接入位于 `com.platform.iot.daikin`：`client` 负责认证、只读路径和受限 HTTP 传输，`mapping` 规范化内外机字段，`catalog` 校验完整目录，`onboarding` 将可信完整目录登记至既有待接入区，并维护来源、项目建筑映射及版本。来源注册不包含凭据或目标 URL；客户端仍须显式启用并注入经确认的请求封装。完整目录入口是内部应用契约，调用者须先完成分页/总数校验并提供可靠轮次时间；`sync` 将目录读取与入库串接为持久化后台任务，不接收浏览器上传厂家目录。

设备接入继续复用 `DeviceOnboardingService`、台账与公共审批。大金状态型产品和绑定通过增量契约支持零数值测点，旧数值绑定规则保留；绑定完成的身份仍停用，启用另走既有审批，身份配置生效不代表正式数据已采集。`/api/v1/daikin/directory` 提供管理员来源登记和项目建筑映射，`/api/v1/device-products/typed-state` 创建状态产品草稿，`/api/v1/operations/device-onboarding` 提供菜单及建筑范围受控的厂家待接入查询、状态维护和逐设备幂等绑定申请；审核执行仍由原管理员入口负责。V47/V48 新增目录结构、外机类型和内外机状态产品草稿，不迁移菜单、不自动启用产品，不写温度/状态历史。后续实施边界见[只读接入设计](docs/designs/2026-09-15-daikin-readonly-integration-design.md)，完成状态以 `PROJECT_STATUS.md` 为准。

目录同步通过 `/api/v1/daikin/sources/{sourceId}/sync-jobs` 提交任务（HTTP 202），通过其 `/{jobId}` 查询状态。复用待接入菜单、提交职责和建筑授权；普通运维只可访问与其建筑有项目映射的来源，任务响应不含全来源设备数量或详情，设备查询继续按建筑过滤。来源内的分页由后台统一读取，首次无映射来源由管理员发起。任务持久化于 V49 新表，包含排队、运行、退避重试、成功和失败状态；HTTP 请求在数据库事务外执行，提交时复核来源租约和执行令牌，再原子提交内外机完整目录。重试重新读取完整清单，不能将前次残页当作最新完整轮次。

同步配置前缀为 `daikin.directory-sync`：`enabled=false`，任务扫描默认 10 秒，来源目录刷新默认 3600 秒，租约默认 120 秒，最多尝试 3 次，基础退避 30 秒，每批最多执行 4 个任务；均可在受限范围内配置，实际频率和租约需结合厂家限流与单页耗时调整。每页请求前核验并续租，过期任务不能续活；多节点部署须同步系统时钟。可信的 `DaikinCatalogClientProvider` 按稳定来源返回只读客户端，不从浏览器接收地址或凭据。同步默认关闭，生产厂家请求封装与来源客户端提供器仍需完成真实联调后装配。启用调度不代表厂家客户端已经可用；没有提供器时不发出外部请求。分钟状态采集、运行时长和页面接线不属于目录同步。
运行监测位于 `daikin.monitoring`，以已绑定且启用的厂家身份、启用产品及项目建筑映射为正式目标。`daikin.monitoring.enabled` 默认 `false`；启用后默认每 60 秒计划一个来源轮次，单后台线程按来源游标处理，最多尝试 3 次、基础退避 5 秒、租约 180 秒。逐页观测先写 V51 临时检查点，再经既有不可覆盖数值接入写 TDengine，最后原子更新 V50 状态和异常；重试保留平台观测时间与测点配置快照，归属或配置改变的旧检查点不转写到新目标。已成功载荷清空，未成功载荷有界保留和退避重放，不能将来源抓取成功等同于跨库数据均已持久化。每页复核来源租约，部分有效页可落盘，整轮失败只在内部重试结束后计一次。

内机类型化产品可显式提供 `temperatureTemplates`（仅 `roomTemp`、`temperature`，单位 `°C`、不参与冷站计算）；绑定申请增加可选 `pointBindings` 与同建筑启用 HTTP 数值来源 `numericSourceId`，别名命名空间固定为 `DAIKIN_V2`。旧零点调用保持兼容，外机仍可零点。模板定义不代表厂家单位已确认：解码策略未确认或正式测点未绑定时，不写入有效温度。已绑定零点产品不通过本次变更静默升级。

V50 保存字段原值与规范值、最近有效时间、本轮候选及状态、归属快照、状态变化和异常实例。缺失、未知、无效字段不清空旧值或恢复故障；温度只保存当前值，变化历史不重复写 MySQL。独立扫描超过 5 分钟未有效观测的设备；连续 3 个失败计划轮次产生单条来源异常。运行状态、滤网维护及厂家故障分开保存，不推断实际操作人或开关时间。`/api/v1/hvac-monitoring` 复用暖通监控菜单与建筑权限，提供设备列表、非温度当前状态、状态事件及异常查询；`devices/{equipmentId}/temperatures` 为单测点 90 天有界游标历史，`temperatures/current` 为最新温度，均执行对应质量使用策略，屏蔽被禁止的数值并保留时间和质量依据。状态事件和已恢复异常只查询最近 365 天，开放异常不因时间过长隐藏；运维页面查询这些持久化结果；厂家统计与保留期任务见下文。既有原始事件默认保留 90 天，正式部署仍需核验 TDengine KEEP 与原始数据保留配置。

运行统计位于 `daikin.runtime`，独立于分钟观测执行器。`daikin.runtime.enabled=false`；可信 `DaikinRuntimeClientProvider` 必须提供已确认的单位、厂家统计时区和语义版本，返回已完成全部分页的规范化批次；这些内部类型不代表厂家报文，生产适配仍须在联调后通过既有只读客户端装配。口径版本或单位/时区改变会拒绝混写，需单独迁移。调度按北京时间 03:00 生成当天计划，启动后补齐最近到期计划；厂家自然日/月/年按明确配置的统计时区计算，使用排他结束时刻。每来源每轮最多执行 2 个期间任务，来源扫描批次默认 2、间隔 10 秒，租约 180 秒、最多 3 次尝试、基础退避 30 秒；失败终止后保留至下一日继续补取，同一目标范围内永久不支持不重试，新增绑定后重新确认能力，超出 365 天停止补取。首次和新增绑定补取保留期内的日/月/年，日常复查最近 7 天、当前月/年，月初/年初 7 天复查上一期间；期间任务落库并按最新期间优先处理。

V52 分别保存厂家日/月/年当前结果和有变化的修订，零值、缺失、不支持、失败分开；失败只更新尝试状态，保留最近成功值及时间，未结束的当前月/年强制标记未完成。字段仅保存有界数值指标和明确单位，不猜测分项含义或相互换算，不叠加不同修订。`/api/v1/hvac-monitoring/devices/{equipmentId}/runtime?granularity=DAY|MONTH|YEAR` 及其 `/{valueId}/revisions` 提供最多 100 条的游标查询；当前值游标为稳定记录 ID，前端按期间展示，修订按版本排序，查询不触发厂家请求。查询同时复核当前绑定身份、建筑和项目映射；即使尚未开始分钟监测，也能查询正式绑定设备的统计。历史归属尚无可靠的全程生效记录，`ownershipVerified=false`，只用于厂家设备历史详情。

保留期任务位于 `daikin.retention`，`daikin.retention.cleanup-enabled` 默认关闭；北京时间 04:15 执行，默认每批 200 行、每轮最多 12 批、租约 120 秒，TDengine 单次最多处理一个子表的 7 天时间窗。V53 保存阶段、游标和租约：状态事件按观测时间保留 365 天，已恢复异常按恢复时间保留 365 天，未恢复异常不删除；统计按期间排他结束时刻保留 365 天，当前未结束月/年自然保留。过期补取先终止，活跃租约仍受保护；超过 90 天的未完成观测明确标记丢弃并记录原因，在途轮次仍保留幂等墓碑。

既有原始事件清理改为真实子表名分页并持久保存进度，默认每轮 100 个子表；普通点继续使用 `data-retention.raw-event-days`，已有大金历史或绑定别名的点至少保留 90 天，混合来源子表使用两者中较长周期。大金独立清理跳过含其他或未知来源的时间窗，并推进游标；混合数据由既有清理按较长保留期回收。[TDengine 删除条件仅支持主键时间列](https://docs.taosdata.com/tdengine-sql/data-write/delete/)，因此不通过普通来源列构造 DELETE。删除标记不等于即时释放磁盘；生产部署仍需核验目标版本、KEEP、时钟同步和积压容量。上述清理不修改目录、设备台账或正式分钟/指标结果。

| 模块 | 稳定职责 |
|---|---|
| `web/src` | 页面、状态编排、API Client、契约类型和可视化；不生成虚假业务数据 |
| `com.platform.system`、`security` | 登录、JWT、角色、菜单和建筑权限 |
| `com.platform.audit` | 后台职责、服务端追踪、安全事件和敏感变更公共闭环 |
| `com.platform.hvac`、`hvac.asset` | 继承的建筑、空间、系统、设备、测点档案及 HVAC 查询 |
| `com.platform.iot.ingest`、`identity` | 标准报文接入、设备身份和归属解析 |
| `com.platform.iot.reliability`、`mqtt` | V2 消息幂等、平台回执、ACK 失败证据与成功监控、TLS 和连接故障分类 |
| `com.platform.iot.quality`、`dataquality`、`qualityusage` | Q0/Q1/Q2 生成、使用策略、运行门禁、纠正和恢复 |
| `com.platform.iot.energymetadata` | 测点能源类型、来源、数据性质、统计周期和专业确认属性 |
| `com.platform.iot.deviceparameter` | 标准设备参数定义、来源、冲突、双时间版本、审核、生效、查询和重算编排 |
| `com.platform.iot.onboarding` | 产品模板、未知设备有界发现、绑定和启停 |
| `com.platform.iot.temporal` | TDengine 时序访问边界 |
| `com.platform.energy.activity` | 多能源原始活动数据的有界读取和质量门禁 |
| `com.platform.energy.catalog`、`conversion` | 能源品种、单位量纲、折标公式和参数版本；研发模拟结果不得冒充正式结算 |
| `com.platform.energy.aggregation`、`period`、`summary` | 活动量聚合、计量事件与修正、周期投影与封账、历史关系和计量边界汇总 |
| `com.platform.energy.efficiency` | 电驱动水冷冷站原生周期、年度 EERp 和研发评价 |
| `com.platform.carbon` | 因子与分母版本、范围一/二计算、追溯、重算和批次审批 |
| `com.platform.relation` | 建筑关系版本、表计层级和方向、计量边界、分层查询及 Excel 草稿导入 |
| `com.platform.cache`、`config` | Redis 缓存和基础设施装配 |
| `telemetry-adapter` | 厂商 Topic 到内部 V2 契约的 MQTT 适配与代理 ACK；不等于边缘持久化网关 |
| `modbus-edge-adapter` | 只读 Modbus TCP/RTU 采集并生成内部 V2 报文；不包含控制或持久化补传 |

### 数据源职责

- MySQL：用户、权限、建筑、空间、设备、测点、配置、关系和业务状态；
- TDengine：原始时序、聚合时序、质量结果和指标结果；
- Redis：Token、权限范围和最新结果缓存，不作为永久事实库；
- MQTT/HTTP：统一北向接入契约；
- WebSocket：实时展示通知，权威结果仍由持久化和受保护查询接口提供。

多数据源必须使用明确 Bean 和 `Qualifier`，不得跨数据源执行 SQL。

### 运维设备原始读数

空调内机使用独立 `IDU` 类型及 `INDOOR_UNIT_METER_1039` 产品/协议族，
[V44](src/env/init/V44__mysql_indoor_unit_meter_template.sql) 只建立七点模板和命名规则，不创建实际设备或身份。
[适配器配置](telemetry-adapter/src/main/resources/db/indoor-unit-meter-1039.example.sql) 默认停用；
正式接入时再补建筑、房间、设备和 SN 归属，并检查 Topic 的唯一协议匹配。

| 报文字段（`/param/ID255/1#` 下） | 测点名称 | 本次确认单位 |
|---|---|---|
| `U` | 内机电压 | V |
| `I` | 内机电流 | A |
| `P` | 内机输入功率 | kW |
| `Pf` | 功率因数 | 无量纲，存储为 `1` |
| `F` | 频率 | Hz |
| `EPP` | 正向电能 | kWh |
| `EPN` | 反向电能 | kWh |

七点默认 `for_calc=0`，不绑定旧 `WCR1_*` 测点，不自动进入冷水机组公式或能源结算。

`GET /api/v1/assets/equipment/{equipmentId}/readings` 沿用资产接口的平台管理员权限，
从 MySQL 确定设备当前测点及单位，再按建筑、设备和测点身份批量读取 TDengine 中各测点的最新原始事件。
返回的是已入库原始值，不是分钟均值；`HAS_DATA` 只表示存在记录，不表示设备在线或运行正常。
读数仍经过 `POINT_REALTIME_VIEW` 质量策略，受阻断时数值为空并返回 `usageStatus/reason`。
`eventTime` 是事件时间，不能默认当作设备采样时间；`receivedTime` 是平台接收时间，
`generatedAt` 是查询组装时间。原始数据已超出保留期时，不能用分钟均值补造原始读数。

`GET /api/v1/assets/equipment/{equipmentId}/trend-history` 使用同一平台管理员权限和设备归属边界，
按 ISO 8601 `startTime/endTime` 半开区间及 `intervalSeconds` 秒级步长查询当前启用测点。
TDengine 按测点分桶计算原始值平均数并保留桶内最差质量等级，应用层再执行
`POINT_HISTORY_VIEW`；被策略禁止的桶不返回数值。单次跨度不超过 90 天，请求步长为下限，
服务会在必要时扩大步长，使单测点最多约 1000 个桶。响应数据点固定为
`[Unix 毫秒时间戳, 测量值]` 元组，不把设备在线状态或现场验收结论混入时序契约。

### 协议配置与预览

接入页面显示为“设备报文接入”，沿用原路由与菜单权限，按产品、映射、解析检查、发布加载、设备接入分阶段操作。
产品名称和设备类型字典为默认展示，原编码与校验值留在技术详情；样例仍不保存。
`POST /api/v1/protocol-deployments/preview` 与 `/rollback-preview` 复用正式发布校验，只计算集合变化，不创建审批或部署；
`GET /api/v1/protocol-deployments/targets/{targetId}/history/{sequence}` 返回历史发布的不可变规则清单。
发布预览比较目标最新期望集合，不能据此判断运行中已加载集合；提交及执行仍重新校验目标序号和冻结内容。
交互及验收边界见[设备报文接入优化设计](docs/designs/2026-09-17-device-onboarding-ux-design.md)。

普通目标发布按 `profileCode` 新增或更新选中版本，自动保留该目标已有的其他协议。
适配器仍接收完整快照；只有显式历史回退才恢复旧的完整集合，可能移除后来新增的协议。
审批冻结合并后的版本列表、目标序号和内容摘要，执行时重新校验，防止并发发布覆盖。
同一精确 Topic 可使用不同 JSON 判别路径；静态校验拒绝重复条件和无条件兜底冲突，
适配器逐条报文必须唯一命中，零命中或多命中均拒绝解析。升级平台和适配器后再发布此类集合。

协议可视化接入提供草稿、预览及受审批的目标发布，页面入口为 `/configuration/ingestion/protocols`，
兼容业务路径 `/system/protocol-configurations`，仅对获菜单授权的平台管理员开放。
`/api/v1/protocol-configurations` 提供分页、详情、创建和按修订号更新；`/inspect` 返回样例字段，
`/preview` 返回设备身份、时间来源、原值与换算值。更新修订过期返回 409；保存草稿不发布规则。

产品模板中的协议编码、身份类型、启用测点和目标单位是映射约束；预览可关联产品草稿，
不等于产品已批准。原始样例不随配置保存，不创建设备或写入时序数据。
`protocol-preview.max-bytes`、`max-depth`、`max-fields` 默认分别为 65536、20、1024；
`requests-per-minute` 默认每个管理员 60 次，当前为单实例有界限流。映射最多 128 项。
HTTP DTO 同时设置固定上限，配置可收紧限制，不能靠调大配置绕过 DTO 上限。

平台与适配器共同编译 [protocol-core](protocol-core/README.md) 无 I/O 解析源码，
保留独立构建入口；打包适配器时需保留相邻共用源码目录。新增存储及管理员菜单由 V45 迁移建立。
`/api/v1/protocol-deployments` 管理目标、不可变版本、历史导入和发布申请；
`PUBLISH_PROTOCOL_CONFIGURATION` 复用公共敏感变更审批，执行时重新核对目标序号、批准产品和绑定别名。
每次发布包含完整启用集合，回退也分配新序号。目标需先通过 HTTPS 联系平台并声明匹配的解析和输出能力；
仅收到对应序号及摘要的加载回执才显示 `LOADED`。默认超过180秒无联系显示 `UNKNOWN`，
可通过 `protocol-publication.contact-timeout-ms` 调整；该状态不是业务遥测持久化 ACK。

适配器专用 `/api/v1/adapter-configurations/{targetId}` 使用独立 `X-Adapter-Key`，
密钥只在登记时返回，平台仅保存摘要，用户 JWT 无法调用该入口；服务拒绝非加密请求。
适配器使用 `ADAPTER_PROFILE_MODE=remote` 主动拉取，HTTPS 证书和主机名校验保持开启。
`jdbc` 是默认兼容模式，两种模式互斥；完整候选快照持久化后才切换，失败保留旧版，冷启动可恢复本地快照。
首次升级前用适配器只读导出功能备份完整旧规则；启用集合关联批准产品后导入不可变版本，禁用规则只归档。
新增存储由 V46 迁移建立。平台仅在本地且云端不可达时，正式云端发布仍未具备部署条件，不能把本地验证称为云端上线。

### 协议与设备接入衔接

协议页面可按协议编码进入待接入列表。产品查询支持 `expectedProfileCode` 与 `identityType` 精确过滤，
先过滤再分页；产品相同不意味着设备身份或建筑相同，绑定仍逐台指定实际归属并经过公共审批。
`GET /api/v1/device-onboarding/naming-rules` 提供启用的命名模板；页面按填写的前缀与产品后缀准备测点编码，
单位沿用产品模板，命名合法性、已有点归属与重复编码由绑定事务重新校验。

绑定后身份保持停用，激活需独立审批。`GET /api/v1/device-onboarding/pending/{pendingId}/connection`
返回绑定的设备、产品与建筑，以及 `UNBOUND`、`INACTIVE`、`ACTIVE` 身份状态。
其中 `configEffective` 只核对身份缓存与当前配置，不证明测点入库、设备在线或现场验收。
接入详情复用设备原始读数接口展示已存储值；没有记录时保持缺失状态，不使用发现样例冒充正式读数。

### 建筑档案接口

`POST /api/v1/assets/buildings` 必须提供建筑名称、类型、总面积、气候区和 `regionCode`；
缺少必填资料返回 400，不由软件猜测行政区划。列表、详情和写入响应返回 `regionCode`。
`PUT /api/v1/assets/buildings/{buildingId}` 未提供行政区划时保留原值，显式提供时不能为空白。

### MySQL 迁移

MySQL 结构由应用启动时的 Flyway 版本链统一推进，迁移源文件位于 [`src/env/init`](src/env/init)，构建时只将 `V*.sql` 打包到 `classpath:db/migration/mysql`。Docker Compose 只创建空数据库，不并行执行 SQL。

新库执行完整版本链；没有 Flyway 历史表的非空旧库默认拒绝启动，必须先核验结构与备份，再按 [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md) 执行一次性受控接管。已成功应用的版本脚本不得原地修改。

### 前端入口与目录边界

前端采用 `index.html → src/app/main.ts` 唯一入口，旧页面、旧全局样式、旧请求客户端及 Ant Design/Tailwind 实现不再保留。进入 `web` 后运行 `npm ci`、`npm run dev`；`npm run build` 构建唯一入口到 `web/dist`。

平台恢复会话时重新向后端核验身份和授权，不信任本地缓存角色。客户端菜单只能使用服务器返回且已在本地注册的叶子路径；目录不自动授予子页面，管理员角色也不自动扩权。迁入管理页面还需保留平台管理员角色检查；前端导航限制不能替代后端接口鉴权和建筑范围校验。

设备台账、设备列表及详情位于“智慧运维 → 设备管理 → 设备台账”（`/operations/devices/businessDevices`）；旧 `/system/devices` 授权和 `/configuration/ingestion/points` 入口仅一对一兼容此页面，保留资产接口的平台管理员要求。

“能碳配置 → 空间与系统 → 设备与空间关联”（`/configuration/space/equipmentSpaces`）单独授权，能源管理员或平台管理员可按建筑查询当前归属及关系版本快照。能源管理员通过现有关系草稿调整空间、系统归属，再提交审核；其他平台管理员审核、生效后，旧台账投影同步更新。页面不直接更新设备档案，也不自动生效整个建筑关系版本。未初始化建筑先从现有明确外键创建草稿；治理生效后既有结构写入限制继续有效。关联页不包含设备运行、能效、报警或维护记录。

| 目录 | 职责与边界 |
|---|---|
| `web/src/app` | 应用装配、路由、办公/监控外壳和全局服务；不实现业务规则 |
| `web/src/modules/<模块>` | 页面、模块组件、API、编排、模型和模块文案；模块间只通过 `public.ts` |
| `web/src/shared` | 无业务归属的组件、图表、组合函数、模型和工具 |
| `web/src/infrastructure`、`generated` | 通用传输能力和生成契约边界；不存放页面或领域计算 |
| `web/src/locales`、`styles` | 公共文案、设计变量、主题和组件库映射 |

监控端采用 1920×1080 逻辑画布等比居中缩放。大屏注册表统一生成路由和切换导航；场景型布局与普通网格布局分开，信息层不得阻断场景交互。图表统一从 `shared/charts` 获取主题、尺寸监听和释放行为。

目录依赖、文案和样式变量由 `web/AGENTS.md` 与 `npm run check:architecture` 共同约束。前端结构、菜单和交付阶段见[前端实施计划](docs/designs/frontend-visualization-phase-two-implementation-plan.md)，当前迁移和验收状态见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

## 5. 目标数据链路

以下是目标链路，不表示每一段都已正式验收：

```text
设备与业务系统
→ 南向协议驱动或边缘适配
→ 字段、单位、时间和身份归一化
→ 统一 MQTT/HTTP
→ 平台接入、归属校验、消息幂等和原始持久化
→ 数据预处理和 Q0/Q1/Q2 质量标识
→ 场景化质量使用策略门禁
→ 多能源活动数据读取、能源品种和单位解析
→ 活动量聚合、计量事件、修正和周期封账
→ 历史关系与计量边界汇总
→ 折标、能效和碳排确定性计算及版本证据
→ 依赖变化影响分析、重算和正式结果审批
→ API/WebSocket
→ 看板、趋势、下钻、能流和碳排展示
```

V2 可靠链只把“全部原始测点已进入 TDengine 且轻量 MySQL 回执已持久化”称为 `PLATFORM_PERSISTED`。Broker `PUBACK`、适配器发布确认、平台消费确认和应用 ACK 发布确认语义不同；成功回执默认只作为热证据，聚合、质量、公式和页面处理不属于该回执语义。

## 6. 空间与语义模型

- 物理空间树表达建筑、楼层、区域、房间的包含关系；
- 可版本化语义关系表达空间、系统、设备、测点、计量边界和服务范围；
- 计量边界与物理边界不得默认相同，分摊规则和未分配状态必须显式表达；
- 表计层级、方向和覆盖对象分开保存，未知事实保持待专业确认；
- Excel 导入固定为“平台模板下载 → 无写预检 → 幂等原子写入指定草稿”，不得自动提交、审核或生效。

## 7. 专业职责边界

| 角色 | 必须提供或确认的内容 | 软件职责 |
|---|---|---|
| 能源专家 | 指标、公式、折算与碳因子、边界、阈值、基准、归因、模型、展示和验收规则 | 将已确认规则实现为可配置、可版本化、可追溯的服务和接口 |
| 硬件人员 | 设备能力、南向协议、字段、单位、采样周期、边缘缓存/补传和现场条件 | 定义统一北向契约、平台接入、状态查询和联调工具 |
| 软件开发人员 | 架构、数据模型、接口、权限、存储、任务、日志、测试和可视化 | 不替能源专家或硬件人员猜测专业输入 |

专业输入未确认时，可以实现配置框架、安全默认和拒绝路径，但不得自行判定哪些指标允许 Q1/Q2、哪些公式有效或哪些设备命令安全。

## 8. 关键入口

| 用途 | 入口 |
|---|---|
| 后端 | [`PlatformApplication.java`](src/main/java/com/platform/PlatformApplication.java)、[`pom.xml`](pom.xml) |
| 前端 | [`web/src`](web/src)、[`web/package.json`](web/package.json) |
| 本地基础设施 | [`src/env/docker-compose.yml`](src/env/docker-compose.yml) |
| Windows 本地一键开发 | 双击 [`start-local-development.cmd`](start-local-development.cmd)；底层脚本使用开发覆盖文件关闭容器自动重启，开发进程结束后只停止容器，不删除命名卷 |
| MySQL 迁移 | [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md)、[`src/env/init`](src/env/init) |
| MQTT 与南向适配 | [`MqttConfig.java`](src/main/java/com/platform/config/MqttConfig.java)、[`telemetry-adapter`](telemetry-adapter)、[`modbus-edge-adapter`](modbus-edge-adapter) |
| 资产与设备接入 | [`com.platform.hvac.asset`](src/main/java/com/platform/hvac/asset)、[`com.platform.iot.onboarding`](src/main/java/com/platform/iot/onboarding) |
| 能源数据与计算 | [`com.platform.energy`](src/main/java/com/platform/energy) |
| 碳管理 | [`com.platform.carbon`](src/main/java/com/platform/carbon)、[`碳管理设计`](docs/designs/2026-09-02-carbon-management-foundation-design.md) |
| 关系治理 | [`com.platform.relation`](src/main/java/com/platform/relation)、[`关系治理设计`](docs/designs/2026-08-26-space-semantic-metering-relation-governance-design.md) |
| 职责与审计 | [`com.platform.audit`](src/main/java/com/platform/audit)、[`职责与审计设计`](docs/designs/2026-08-26-backoffice-duty-audit-governance-design.md) |
| 当前状态 | [`PROJECT_STATUS.md`](PROJECT_STATUS.md) |
| Git 与验证 | [`repository-guardrails.md`](docs/development/repository-guardrails.md)、[`iot-change-verification`](.agents/skills/iot-change-verification/SKILL.md) |
| 旧系统历史 | [`docs/superpowers/README.md`](docs/superpowers/README.md)、[`设计冻结书`](docs/设计冻结书-V1.0-19测点.md) |

### 产品类型与研发审批

产品编辑器通过 `/api/v1/device-products/equipment-types` 选择已启用类型，产品页复用公共审批控件完成提交、审核和执行。V54 复用 V48 的 ODU 分类，补充 `ODU[n]` 命名规则，不预置电表协议、测点或真实设备。

本机研发可显式设置 `AUDIT_ENVIRONMENT_MODE=DEVELOPMENT`（隔离验收用 `TEST`）与 `AUDIT_ALLOW_SELF_APPROVAL=true`，服务绑定到 `127.0.0.1`。默认自审关闭；`PRODUCTION` 与自审同时开启时后端拒绝启动。环境模式由部署正确声明。
同一管理员仍需提交和审核两项后台职责，依次提交、审核、执行并保留审计记录；开关不授予职责。
受鉴权保护的 `GET /api/v1/backoffice/change-requests/policy` 返回有效审批模式供页面提示，不替代后端权限校验。生产部署关闭自审并使用独立审核账号。

## 9. 更新规则

- 稳定定位、模块边界、数据源职责、核心链路或运行入口变化时更新本文件；
- 当前阶段、完成项、验证结果、风险和下一步只写入 `PROJECT_STATUS.md`；
- 具体实现和验收细节写入相应设计或评审文档，本文件只保留入口；
- 当前行为始终以所在 Git 版本的代码和测试为准。

### 大金运维页面入口

- 智慧运维 → 设备管理 → 待接入设备：`/operations/devices/pendingDevices`。既有 `/system/device-onboarding` 与 `/configuration/ingestion/pendingDevices` 一对一兼容；V55 保持菜单 ID、角色授权和可见状态，仅迁移叶子路径。厂家配置、凭据及产品模板继续留在配置侧。
- 智慧运维 → 实时监控 → 暖通空调监控：在既有入口增加厂家设备页签，保留冷站监测；按授权建筑展示设备名称/编码、当前状态及温度、状态变化、厂家运行统计与修订。台账接入分栏可跳转监测。
- 实时报警、历史报警使用已有注册路径。对应报警叶子只允许对应异常列表，不扩展设备状态、温度或运行统计权限；原暖通监测授权及建筑范围继续生效。
- 浏览器每分钟刷新平台当前结果，隐藏或卸载停止刷新，不触发厂家请求；历史与统计按游标翻页，不将一页当完整时段。温度缺口及质量禁止值不插值；厂家日/月/年统计、零值、失败与未完成周期独立展示，不跨版本求和或推断历史房间归属。
