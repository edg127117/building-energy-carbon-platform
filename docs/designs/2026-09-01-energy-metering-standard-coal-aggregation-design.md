# 第七闭环：能源计量、折标与时段聚合设计

## 1. 文档状态与权威边界

本文是阶段一“能源计量与能耗管理”第七闭环的正式候选设计。设计讨论已经确认业务方向，
但在对应代码、自动化验证、独立候选验收和用户批准完成前，不代表平台已经具备正式能源
计量能力，更不代表生产、真实设备或现场验收完成。

本文只设计后端能源字典、单位量纲、活动数据聚合、折标煤、周期结果、多维查询、封账、
追溯重算、权限、审计和模拟验收。本文不修改既有采集、关系和质量治理的权威职责，
下游实现必须消费它们的公开契约，不得直接读取上游内部表。

当前没有试点建筑和现成硬件设备。用户提供的历史碳盘查 Excel 样例及截图只可作为研发
基线来源，必须标记“待专业确认、仅研发模拟、生产不可用”。模拟数据和模拟维护事件不得
描述为真实设备、真实建筑或现场证据。

## 2. 目标与非目标

### 2.1 目标

本闭环形成以下后端能力：

1. 建立可配置、可版本化、可追溯的能源品种和单位量纲字典；
2. 建立测点与权威能源品种的版本化绑定；
3. 按累计量、周期量和瞬时量分别处理活动数据；
4. 处理复位、回绕、换表、错误修正、迟到和质量门禁；
5. 按日、月、年周期形成当前投影和月度正式封账结果；
6. 使用低位热值、直接折标煤系数或能量当量计算 `kgce/tce`；
7. 在计量边界上形成唯一权威结果，并按建筑、空间、系统、设备、能源品种等维度查询；
8. 显式返回未分配、总分表差额、质量覆盖率、例外和全部计算版本证据；
9. 使用研发模拟数据完成确定性软件验收。

### 2.2 非目标

本闭环明确不包含：

- 移动源燃烧；
- `tCO2`、电力碳因子、单位热值含碳量、碳氧化率和 `44/12` 碳计算；
- 外购冷量和蒸汽正式折标；
- 峰、平、谷电价时段及费用结算；
- 未确认的面积、人数、运行时间、功率或平均分摊；
- Vue 管理页面、能耗看板、图表、下钻交互和其他可视化；
- 真实设备、真实网络、现场精度和长期运行验收；
- 远程控制、指令下发和设备参数下发。

## 3. 已具备的上游公开依赖

第七闭环只通过公开契约使用以下能力：

| 依赖 | 当前用途 | 本闭环不得做的事情 |
|---|---|---|
| 多能源活动数据读取 | 读取建筑、测点、半开时间区间内通过质量门禁的原始活动数据 | 不读取 TDengine 内部表，不复用 HVAC 查询 DTO |
| 有效计量分配查询 | 取得测点、计量边界、目标空间/系统/设备、表计结构、方向和关系版本 | 不读取关系治理内部表，不自动补造关系 |
| 能源测点专业属性 | 取得粗分类、电力来源、数据语义、单位引用、确认状态和配置修订 | 不复制测点单位和用能系统 |
| `ENERGY_ACTIVITY_AGGREGATION` | 按测点和场景门禁 Q0/Q1/Q2 | 不在本闭环重新生成质量等级 |
| 建筑权限、后台职责、审计和 `traceId` | 建筑范围、动作职责、审核与追溯 | 不把职责固化进 JWT |

现有活动数据按“测点 + 事件时间”提供合并后的当前事实，没有保留完整重复次数和修正历史。
正式可追溯计算仍需本文第 8 节定义的追加式异常与修正证据层。

## 4. 领域职责与模型边界

### 4.1 四层职责

| 层级 | 权威职责 | 禁止混入的内容 |
|---|---|---|
| 采集粗分类 | 保留 `ELECTRICITY/NATURAL_GAS/HEAT/COLD/FUEL` 兼容分类 | 不直接选择折标公式 |
| 能源品种字典 | 表达电力、天然气、烟煤、柴油等权威计算对象 | 不保存测点原始单位和折标参数 |
| 单位量纲字典 | 表达单位身份、量纲和固定比例换算 | 不保存低位热值、密度、焓值和碳因子 |
| 折标规则 | 绑定能源品种、计算方法、参数、适用范围和生效版本 | 不修改测点档案和能源品种定义 |

现有粗分类仅用于采集兼容和一致性校验。第七闭环的计算分类是能源品种，折标计算不得同时
按粗分类和能源品种各计算一次。现有 `energySubtype` 继续只表达电力来源，不得复用为柴油、
汽油等能源品种。

### 4.2 首批能源品种与使用范围

首批能源品种来自用户确认的红框范围：

| 编码 | 名称 |
|---|---|
| `BITUMINOUS_COAL` | 烟煤 |
| `ANTHRACITE` | 无烟煤 |
| `LIGNITE` | 褐煤 |
| `DIESEL` | 柴油 |
| `GASOLINE` | 汽油 |
| `FUEL_OIL` | 燃料油 |
| `KEROSENE` | 一般煤油 |
| `LPG` | 液化石油气 |
| `LNG` | 液化天然气 |
| `NATURAL_GAS` | 天然气 |
| `COKE_OVEN_GAS` | 焦炉煤气 |
| `ELECTRICITY` | 电力 |
| `HEAT` | 外购热力所使用的热量品种 |

能源品种与使用范围分开保存：

```text
STATIONARY_COMBUSTION
MOBILE_COMBUSTION
PURCHASED_ELECTRICITY
PURCHASED_HEAT
```

本闭环允许 `STATIONARY_COMBUSTION`、`PURCHASED_ELECTRICITY` 和 `PURCHASED_HEAT`，
明确拒绝 `MOBILE_COMBUSTION`。柴油和汽油不能因移动源被排除而从字典删除，因为固定锅炉、
固定发电机等场景仍可能使用这些能源。

电力仍是一个能源品种。电网购入、交易购入、可再生能源直供和自发电属于来源属性，
购入和外送属于流向属性。“净外购电量”是购入减外送的派生结果，不建立为能源品种。

### 4.3 能源品种版本

能源品种稳定身份与版本定义分开保存。最小字段为：

```text
itemId
itemCode
versionId
versionNo
itemName
compatibleCategory
status: DRAFT/PENDING_EXPERT/APPROVED/DISABLED
sourceType: STANDARD/EXCEL/MANUAL
sourceReference
effectiveFrom/effectiveTo
createdBy/createdAt
approvedBy/approvedAt
```

`itemCode` 不随名称或参数变化而变化。定义修改产生新版本，不覆盖已经被历史结果引用的版本。
兼容粗分类必须显式配置；不一致时拒绝正式计算，不根据能源名称自动推断。

### 4.4 测点—能源品种绑定

`biz_data_point.unit` 继续作为测点原始单位的唯一权威。能源品种绑定只保存引用和业务生效时间：

```text
bindingId
buildingId
pointId
energyItemCode
effectiveFrom/effectiveTo
bindingVersion
confirmationStatus
evidenceReference
createdBy/createdAt
approvedBy/approvedAt
```

同一测点在同一业务时间只能有一个有效能源品种。绑定修改产生新版本，历史计算保存绑定版本。
禁止根据测点编码、名称、单位或粗分类自动补造能源品种。

## 5. 单位量纲设计

### 5.1 首批单位

| 单位编码 | 符号 | 量纲 | 用途 |
|---|---|---|---|
| `KW` | kW | `POWER` | 瞬时功率 |
| `KWH` | kWh | `ENERGY` | 电量 |
| `MWH` | MWh | `ENERGY` | 电量 |
| `MJ` | MJ | `ENERGY` | 热量 |
| `GJ` | GJ | `ENERGY` | 热量 |
| `M3` | m3 | `ACTUAL_VOLUME` | 实际状态体积 |
| `NM3` | Nm3 | `NORMAL_VOLUME` | 标准状态体积 |
| `TEN_THOUSAND_NM3` | 万Nm3 | `NORMAL_VOLUME` | 标准状态体积汇总量 |
| `KG` | kg | `MASS` | 燃料质量 |
| `T` | t | `MASS` | 燃料质量 |
| `KGCE` | kgce | `STANDARD_COAL_EQUIVALENT` | 折标结果 |
| `TCE` | tce | `STANDARD_COAL_EQUIVALENT` | 折标结果 |

单位最小字段为：

```text
unitCode
symbol
unitName
dimensionCode
canonicalUnitCode
scaleFactor
conversionType
standardConditionCode
precision
versionNo
status
sourceReference
```

### 5.2 固定比例换算

首批只允许：

```text
1 MWh = 1000 kWh
1 GJ = 1000 MJ
1 t = 1000 kg
1 万Nm3 = 10000 Nm3
1 tce = 1000 kgce
```

以下不是普通单位换算：

- `kW -> kWh` 需要时间积分；
- `m3 -> Nm3` 需要温度、压力和标准状态；
- `kg <-> m3` 需要密度；
- 蒸汽 `t -> GJ` 需要压力、温度和焓值；
- `GJ -> tce` 属于折标业务计算；
- 任意能源量到 `tCO2` 属于后续碳核算。

### 5.3 能源品种—单位兼容矩阵

显式保存：

```text
energyItemCode
unitCode
valueSemantics
allowed
conversionRequirement
versionId
status
sourceReference
```

`tce/kgce` 只作为计算输出，首版不作为普通原始活动数据单位。`Nm3` 必须携带标准状态依据；
没有依据时保持原单位但不得与其他标准体积正式合并。

## 6. 聚合输入契约

### 6.1 原始活动数据职责不变

现有活动数据契约继续负责测点、原始单位、原始值、事件时间、入库时间、质量、迟到标记、
来源和已有策略证据，不携带折标参数、封账状态或计算结果。

### 6.2 测量上下文

能源测点元数据通过独立公开契约补充：

```text
pointId
energyItemCode
itemBindingVersionId
sourceUnitCode
unitDefinitionVersionId
valueSemantics
standardConditionCode
dataNature: REAL/SIMULATED
confirmationStatus
effectiveFrom/effectiveTo
evidenceReference
```

### 6.3 周期量事实

`PERIOD_TOTAL` 必须额外表达：

```text
sourcePeriodStart
sourcePeriodEnd
sourcePeriodTimezone
periodDefinitionVersion
```

缺少源周期边界时只能保存原值，不能进入正式周期汇总。累计量和瞬时量的上述字段为空。

### 6.4 聚合输入端口

新增内部稳定端口 `EnergyAggregationInputPort`，组合：

```text
活动数据
+ 测量上下文
+ 有效计量分配
+ 计量事件
+ 审核修正
+ 质量策略证据
```

每次计算固定：

```text
calculationAsOf
activityWatermark
relationVersionId
pointBindingVersionId
qualityPolicyVersions
meterEventVersions
correctionVersions
```

聚合核心只消费该端口，不直接访问上游数据库或 Mapper。现有活动 DTO 的数值边界是 `double`；
聚合输入和公式计算必须使用十进制数。上游 TDengine 实际字段精度需要隔离验证，研发算法验证
不得被描述为结算级计量精度。

## 7. 三类活动数据的聚合规则

### 7.1 累计量 `CUMULATIVE`

正常增量：

```text
delta = currentReading - previousReading
```

周期计算必须取得开始前最近有效锚点和周期结束边界所需的有效读数。没有起点锚点时，禁止把
周期内第一条累计读数当作本期能耗。负增量不得统一取绝对值，必须进入第 8 节的分类流程。

### 7.2 周期量 `PERIOD_TOTAL`

周期量按显式源周期汇总，必须检查完整覆盖、间断、重叠和重复。月度总量不得再与组成它的
日数据重复求和。缺少源周期边界时拒绝正式聚合。

### 7.3 瞬时量 `INSTANTANEOUS`

瞬时量需要版本化积分策略：

```text
STEP_PREVIOUS
TRAPEZOIDAL
```

策略同时配置最大间隔、最低覆盖率和边界处理。未配置积分方法、间隔超过允许值或覆盖不足时，
不得自动补零、取平均值或填满缺失时段。`kW` 不能直接相加形成 `kWh`。

## 8. 复位、回绕、换表、错误与修正

四种情况共用事件治理框架，但使用独立字段、校验和计算处理器。

### 8.1 公共事件字段

```text
eventId
buildingId
meterPointId
eventType: RESET/ROLLOVER/REPLACEMENT/DATA_ERROR
occurredAt
sourceType
evidenceReference
status
versionNo
createdBy/createdAt
approvedBy/approvedAt
simulationFlag
```

检测到负增量时只创建“待分类异常”。只有已审核事件可以改变正式计算。

### 8.2 复位

保存复位前、复位后读数和原因，按复位时刻分段：

```text
复位前增量 + 复位后增量 = 本期总量
```

### 8.3 回绕

保存 `rolloverModulus`，不只保存最大显示值。例如显示 `0..9999` 的计数周期是 `10000`。
没有计数周期时拒绝计算。

### 8.4 换表

保存换表时间、旧/新表身份、旧表最后读数、新表初始读数和前后关系版本。旧表和新表分别
结算后相加，不允许拿新表读数减旧表读数。

### 8.5 数据错误与修正

原始错误数据必须保留，修正只追加：

```text
originalFactIdentity
originalValue
correctionId
correctionVersion
correctedValue
correctionReason
status
approvedAt
evidenceReference
```

存在唯一已审核修正时使用修正值并保留原值引用；存在修正冲突时拒绝计算。能源模块不得自行
重新放行被质量策略拒绝的原始行；修正事实必须先经过质量/修正治理并重新通过聚合场景门禁。

当前原始事实没有完整修正历史，因此追加式异常与修正证据是正式累计量验收前必须完成的
能力，不得用当前合并事实冒充完整追溯证据。

## 9. 折标煤参数与公式

### 9.1 计算方法

首批支持：

```text
DIRECT_TCE_FACTOR
LOWER_HEATING_VALUE
ENERGY_EQUIVALENT
```

同一能源品种、适用范围、业务时间和计算口径只能匹配一条有效规则。找不到或匹配多条时拒绝
正式计算，不自动选择“最新参数”。

### 9.2 标准煤低位热值

标准煤低位热值作为独立、版本化参数保存：

```text
29307.6 kJ/kgce
= 29.3076 MJ/kgce
= 29.3076 GJ/tce
```

该值必须保存标准来源和版本，不得作为无法追溯的代码常量。

### 9.3 固定燃料和气体燃料

质量燃料：

```text
tce = 消耗量(t) * 低位热值(GJ/t) / 29.3076(GJ/tce)
```

气体燃料：

```text
tce = 消耗量(万Nm3) * 低位热值(GJ/万Nm3) / 29.3076(GJ/tce)
```

用户提供 Excel 中的低位热值只作为 `PENDING_EXPERT + DEVELOPMENT_SIMULATION` 研发基线。

### 9.4 外购热力

```text
tce = 外购热量(GJ) / 29.3076(GJ/tce)
```

历史表格中的热力碳排放因子不参与本公式。

### 9.5 电力当量值与等价值

当量值：

```text
1 kWh = 3.6 MJ
kgce = kWh * 3.6 / 29.3076
```

等价值使用单独的已审核系数。软件不得使用电力碳排放因子替代等价值系数，也不得把两种口径
写入一个含义不明的 `tce` 字段。如需同时查询，分别形成：

```text
tce_calorific_equivalent
tce_primary_equivalent
```

综合能耗只能汇总同一计算口径。

### 9.6 参数和证据

折标参数至少保存：

```text
parameterId/parameterCode
energyItemCode
method
parameterValue/parameterUnit
standardCoalLhvVersion
applicableInputUnit
consumptionScope
regionCode
effectiveFrom/effectiveTo
sourceType/sourceReference
status/versionNo/usageScope
createdBy/approvedBy
```

公式和参数分开版本化。计算结果保存公式、参数、标准煤热值、单位、绑定、关系、质量策略、
聚合策略和舍入规则的具体版本。全部中间计算使用十进制数且不提前舍入。

## 10. 计量边界、多维汇总与去重

### 10.1 权威结果粒度

权威结果按以下组合保存：

```text
建筑
+ 计量边界
+ 能源品种
+ 能源来源
+ 流向
+ 周期
+ 折标口径
```

空间、楼层、系统和设备是基于固定关系版本的归属维度，不分别复制完整权威台账。

### 10.2 总分表口径

每个边界显式选择：

```text
MAIN_METER_TOTAL
SUBMETER_SUM
INDEPENDENT_METER_SUM
MAIN_WITH_SUBMETER_BREAKDOWN
```

总表和分表不得直接相加。总表决定总量时，分表只解释内部构成，差额保存为
`UNATTRIBUTED_RESIDUAL`。没有总表时，只有已确认互不重叠的分表或独立进线才允许求和。
角色为 `UNKNOWN`、关系待确认或覆盖范围可能重叠时拒绝正式边界总量。

### 10.3 未分配

```text
ASSIGNED
PARTIALLY_ASSIGNED
UNALLOCATED
UNATTRIBUTED_RESIDUAL
```

首版不做自动分摊。一个表计服务多个对象且没有已审核分摊规则时，全部能耗保留在未分配桶；
不得自动平均、按面积、人数、运行时间或功率分配。建筑边界总量可以保留，但空间/系统查询
必须同时返回未分配量，不能让下级合计看起来等于完整总量。

### 10.4 购入、外送与净值

分别保存：

```text
grossInbound
grossOutbound
netQuantity = grossInbound - grossOutbound
```

只有计量边界、能源品种、来源口径、周期和单位一致且方向已确认时才能计算净值。原始购入量
和外送量不得被净值覆盖。

### 10.5 多维查询

后端支持 `BUILDING/METERING_BOUNDARY/SPACE/SYSTEM/DEVICE/ENERGY_ITEM/ENERGY_SOURCE/
FLOW_DIRECTION/PERIOD` 分组。不同原始量纲分别返回；只有相同折标口径的 `tce` 才能跨能源
汇总。历史查询默认使用结果记录的关系版本；按新关系重述历史必须走追溯重算。

## 11. Q0/Q1/Q2、覆盖率与完整性

### 11.1 质量门禁

第七闭环不重新定义质量生成。无有效策略时只允许 Q0，显式空集合拒绝全部。Q1/Q2 必须通过
已审核的 `ENERGY_ACTIVITY_AGGREGATION` 策略开启，不得因允许例外封账而绕过质量门禁。

迟到、重复、修正和质量等级分别处理。迟到数据可能是 Q0；是否允许加入取决于质量策略和
周期状态。

### 11.2 语义化完整性

- 累计量检查起止锚点、边界质量和未解决中断，不按数据条数判断；
- 周期量按显式源周期的完整覆盖、间断和重叠判断；
- 瞬时量按有效积分时长、最大连续缺口和最低覆盖率判断。

结果完整性：

```text
COMPLETE
COMPLETE_WITH_ALLOWED_QUALITY
INCOMPLETE
BLOCKED
```

质量证据至少保存各等级接受数量、拒绝数量、覆盖率、最大缺口、起止锚点状态、未解决问题数、
质量策略版本集合和例外策略版本。为控制存储量，周期结果保存汇总与输入摘要，不复制全部原始
质量记录。

## 12. 周期、封账、例外与追溯重算

### 12.1 周期边界

所有业务周期以 `eventTime` 归属，使用半开区间 `[startInclusive, endExclusive)`。首批支持
自然日、自然月和自然年。研发默认时区为 `Asia/Shanghai`，但必须作为版本化研发配置；
生产时区必须由建筑配置确认。月不得按固定 30 天、年不得按固定 365 天计算。

首批不实现峰、平、谷和任意自定义结算周期。

### 12.2 封账周期

研发基线：

```text
日：维护暂定结果，不默认正式封账
月：主要正式封账周期
年：汇总 12 个已封账月度结果
closingDelayHours = 72
lockMode = REVIEW_REQUIRED
```

72 小时只用于研发模拟，必须标记待业务确认，不能写成生产事实。达到时间只触发完整性检查，
不保证一定封账。

### 12.3 状态

周期当前状态：

```text
OPEN
PROVISIONAL
```

正式快照状态：

```text
LOCKED_COMPLETE
LOCKED_WITH_EXCEPTIONS
LOCKED_PARTIAL
SUPERSEDED
INVALIDATED
```

开放期只维护一个当前投影和递增的并发修订号，不保存每次迟到数据产生的完整暂定版本。

### 12.4 封账检查

封账至少检查周期结束与迟到窗口、能源品种、计量关系、单位、折标规则、质量策略、累计锚点、
计量事件、瞬时量覆盖率、周期量边界、流向和未解决异常。失败必须返回具体阻塞原因，禁止以
零值或默认归属伪造正常结果。

### 12.5 例外策略

例外策略是版本化白名单：

```text
issueCode
severity
lockAction
maximumAffectedCount/Ratio
minimumCoverage
applicableScope
requiresApproval
requiredEvidence
effectiveFrom/effectiveTo
policyVersion/status
```

动作只能是：

```text
BLOCK
ALLOW_WITH_EXCEPTION
EXCLUDE_AFFECTED_AND_LOCK
LOCK_NATIVE_QUANTITY_ONLY
```

例外策略不能通过任意脚本“忽略错误”。单位量纲冲突、缺少折标规则、未确认能源品种或关系
时，可以封账已知原始量或不受影响范围，但不能生成受影响的正式 `tce`、默认归属或猜值。

### 12.6 封账后重算

已封账结果永不原地覆盖。迟到、修正或规则追溯按一个批次组织多个设备和边界：

```text
LOCKED V1
-> 重算申请与审核
-> 增量计算批次
-> LOCKED V2
-> V1 标记 SUPERSEDED
```

数据修正重算默认使用原周期的关系、质量、聚合和折标参数版本。使用新规则重述历史必须单独
申请，明确追溯范围和新规则版本。

## 13. 结果存储、批处理与一致性

### 13.1 存储职责

MySQL 保存字典、版本、绑定、策略、计量事件、修正、工作流、封账状态、重算批次、结果索引、
当前版本指针和审计。TDengine 保存原始活动数据、当前周期数值投影、正式数值快照和数值质量
摘要。Redis 只作热点缓存和短期任务进度，丢失后必须可以重建。

### 13.2 当前投影和增量快照

逻辑上区分：

```text
energy_period_result_current
energy_period_result_snapshot
energy_recalculation_batch
energy_dirty_period
```

开放期迟到数据更新当前投影，不生成完整历史副本。待重算集合按“建筑 + 计量边界 + 能源品种
+ 周期”去重并合并事件。正式重算建立一个批次，只追加真正变化的结果和受其影响的上级汇总；
未变化对象不复制。

### 13.3 跨库一致性

不使用分布式事务。批次状态：

```text
CREATED -> VALIDATING -> CALCULATING -> WRITING_RESULTS -> COMPLETED
```

MySQL 先创建批次和幂等键，TDengine 使用确定性结果键写入，成功后再更新 MySQL 索引。查询只
读取 `COMPLETED` 批次。程序在 TDengine 写入后崩溃时，使用同一结果键重试；未完成批次和
孤立数值不得对外可见。

大批量重算必须分页、分片、限并发、批量写入、断点续算和失败重试。批次只有全部完成后才
切换可见状态。参数追溯确实影响全部对象时，受影响结果仍必须重算；设计只能避免复制未变化
结果，不能隐藏真实业务写入量。

## 14. 权限、职责分离与审计

继续使用正式角色、建筑范围和动态后台职责。建议动作职责：

```text
ENERGY_CATALOG_MAINTAIN / ENERGY_CATALOG_REVIEW
ENERGY_RULE_MAINTAIN / ENERGY_RULE_REVIEW
ENERGY_CALCULATION_RUN
ENERGY_LOCK_SUBMIT / ENERGY_LOCK_APPROVE
ENERGY_EXCEPTION_APPROVE
ENERGY_RECALC_SUBMIT / ENERGY_RECALC_APPROVE
ENERGY_RESULT_EXPORT
```

生产环境中维护人与审核人、封账提交人与审核人、重算申请人与审核人必须分离。研发自审只能
在非生产配置中完整执行提交和审核两个动作，并保留审计；生产误开时拒绝启动。

审计至少记录 `traceId`、执行人、动作、建筑范围、对象、前后版本、原因、策略版本和时间。
大量数值不复制进审计表，只保存批次、数量、摘要和结果哈希。正式结果、被替代结果、参数历史、
计量事件、修正和审批受保留策略保护；具体保留年限待制度确认。

## 15. 查询结果与错误边界

多维查询至少返回：

```text
groupKey
originalQuantities
tceByPerspective
assignedQuantity
unallocatedQuantity
residualQuantity
coverageRatio
exceptionCount
lockStatus
resultCompleteness
relationVersionId
conversionRuleVersion
resultNature
```

`resultNature` 至少区分：

```text
DEVELOPMENT_SIMULATION
FORMAL
```

只有真实活动数据、已确认绑定与关系、已审核参数和策略、满足完整性要求且完成正式工作流时，
结果才可能是 `FORMAL`。模拟输入或研发基线参数产生的结果必须是
`DEVELOPMENT_SIMULATION`。

稳定失败至少覆盖：能源品种缺失、单位不兼容、活动语义缺失、周期边界缺失、累计锚点缺失、
负增量待分类、关系未确认、折标规则缺失或冲突、质量策略拒绝、覆盖不足、封账阻塞、重算冲突、
建筑越权和职责不足。未知问题默认安全拒绝。

## 16. 模拟验收矩阵

### 16.1 字典、单位和范围

- `MWh/kWh`、`GJ/MJ`、`t/kg`、`万Nm3/Nm3`、`tce/kgce` 固定换算；
- `kW -> kWh`、`m3 -> Nm3`、`kg <-> m3`、蒸汽 `t -> GJ` 无规则拒绝；
- 固定源柴油允许，移动源柴油拒绝；
- 模拟数据和研发参数不能产生正式结果。

### 16.2 折标

- `29.3076 GJ` 外购热力得到 `1 tce`；
- 固定燃料和气体按低位热值公式得到人工预期结果；
- 电力当量值和等价值分别输出；
- 两条规则同时匹配、参数过期、状态不允许和单位不兼容时拒绝；
- 结果中不存在 `tCO2`。

### 16.3 活动语义和计量事件

- 正常累计量；
- 复位前后分段；
- 配置计数周期的回绕及无周期拒绝；
- 换表前后分别结算；
- 未分类负增量阻塞；
- 原始错误值保留，审核修正后重新计算；
- 周期量完整、缺失、重叠和无边界；
- 瞬时量阶梯法、梯形法、最大缺口和覆盖不足。

### 16.4 关系、质量和封账

- 总表 `1000 kWh`、两处分表 `300/400 kWh` 得到总量 `1000`、差额 `300`，不得得到 `1700`；
- 未确认关系不归属，多对象无分摊时进入未分配桶；
- 购入 `1000`、外送 `200` 得到净值 `800` 且保留两项原值；
- 无质量策略只允许 Q0，Q1/Q2 需审核策略，例外封账不绕过门禁；
- 累计量、周期量和瞬时量分别计算完整性；
- 开放期迟到数据只更新当前投影；
- 完整、带例外和部分封账；
- 封账后多个对象只建立一个重算批次，只追加变化结果；
- 重试同一幂等键不重复，未完成批次不可见。

### 16.5 权限和基础设施

- 建筑越权、职责不足、维护人与审核人相同稳定拒绝；
- H2/Mock 只验证隔离业务行为；
- 隔离 MySQL 验证迁移、约束、并发、状态机和审核；
- 隔离 TDengine 验证排序、聚合、幂等写入、批量结果和查询计划；
- 合成闭环验证模拟活动数据到聚合、折标、封账和重算。

上述证据只支持“软件模拟闭环”，不支持真实设备、现场精度、生产容量或标准整体符合性声明。

## 17. 分阶段实现与验收门

实现按单一问题拆分，后续每个分支和 PR 只处理一个明确范围：

1. 能源品种、单位量纲、兼容矩阵和测点绑定；
2. 折标参数、公式版本和确定性 `tce` 计算核心；
3. 聚合输入端口、三类活动语义、计量事件和修正证据；
4. 周期当前投影、封账、例外、重算批次和跨库一致性；
5. 计量边界汇总、未分配、多维查询和完整模拟验收。

进入下一实现任务前必须重新基于最新 `origin/main` 创建任务分支并执行仓库预检。任何一个实现
PR 的 H2、Mock 或合成测试都不能替代 MySQL、TDengine、真实设备或现场验收。完整代码通过、
模拟验收通过和用户批准正式版本是不同状态。

## 18. 仍待外部确认

以下内容不得由软件自行决定：

- 首批能源品种与现有粗分类的最终专业映射；
- Excel 低位热值、直接折标系数及其标准来源；
- 电力等价值系数、适用地区、年份和口径；
- 正式舍入精度和舍入方式；
- 瞬时量积分方法、最大间隔和最低覆盖率；
- `Nm3` 标准状态条件；
- 生产封账迟到窗口、例外阈值和结果保留年限；
- 生产规模、MySQL/TDengine 容量和批处理并发参数；
- 真实表计、计量事件、关系、活动数据和人工预期 `tce` 验收样例。

缺少上述输入时，软件可以实现研发配置框架、安全默认、拒绝路径和模拟验收，但不得产生或
宣称生产可用的正式能源结果。
