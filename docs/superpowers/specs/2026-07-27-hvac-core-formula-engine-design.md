# HVAC 核心公式引擎设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 1. 目标

在现有 19 测点接入、质量校验、TDengine 原始事件留痕、分钟冻结和恢复补漏基础上，实现本期全部启用的单体设备能效公式，并完成以下闭环：

```text
HvacMinuteBatchFrozenEvent
→ 公式计算
→ 指标分钟结果落库
→ Redis 最新状态
→ WebSocket 实时推送
→ 指标与计算过程查询
```

本设计实现：

- 公式 5-1：冷水机组制冷量；
- 公式 5-2：冷水机组 COP；
- 公式 5-4：冷却塔效率；
- 公式 5-5：水泵效率；
- 公式 5-6：水泵平均扬程；
- 公式 5-7：AHU 单位风量耗功值。

公式 5-3 适用于溴化锂吸收式冷水机组。试点建筑使用电制冷机组，因此本期不实现、不调度，也不注册运行指标。

## 2. 范围边界

### 2.1 本期包含

- 四个最终指标：
  - `WCR_COP`
  - `TOWER_EFF`
  - `PUMP_EFF`
  - `AHU_POW_EFF`
- `Q₀`、`Nᵢ`、`ΔH`、派生湿球温度等中间结果；
- 正常分钟事件驱动计算；
- 恢复事件的完整分钟输入回查；
- 指标独立低频缺口补算；
- 指标质量透传；
- 成功指标轻量存储；
- 缺失与物理异常审计；
- Redis 最新结果缓存；
- WebSocket 指标状态推送；
- 最新指标、指标历史和公式过程查询接口；
- 自动化测试与实际基础设施冒烟验证。

### 2.2 本期不包含

- 公式 5-3；
- 系统级 COP；
- 联合调适、动态控制和指令下发；
- 质量等级 1 的插值数据生成；
- 质量等级 2 的典型值或 `default_value` 自动补全；
- 默认值审批、有效期管理和人工审核页面；
- 动态公式 DSL 或通用规则引擎；
- 50 栋建筑下的虚拟线程并行计算和分布式锁。

### 2.3 数据质量边界

现阶段正式分钟数据只产生质量等级 `0`。公式引擎从第一版开始支持 `0/1/2` 的质量透传，但不负责生成质量 `1/2` 数据。

同一分钟缺少必需输入时严格跳过对应指标：

- 不读取上一分钟最新值；
- 不跨分钟拼接；
- 不自动套用默认值；
- 不把缺失或异常结果写成零；
- 记录具体缺失输入或物理异常原因。

后续数据补全层生成质量 `1/2` 的标准分钟数据后，公式引擎无需修改数学公式。

## 3. 总体架构

采用事件驱动方案，复用现有分钟冻结出口：

```text
MQTT 数据
→ TelemetryQualityValidator
→ st_raw_event
→ HvacMinuteAggregationService
→ st_raw_minute
→ HvacMinuteBatchFrozenEvent
→ HvacFormulaEngine
→ 设备公式计算器
→ IndicatorMinuteRepository
→ st_indicator_minute
→ Redis
→ WebSocket
```

### 3.1 公式编排引擎

`HvacFormulaEngine` 监听 `HvacMinuteBatchFrozenEvent`，负责：

1. 获取事件对应的完整分钟输入；
2. 过滤 `isForCalc=1` 的测点；
3. 按建筑、设备、组件和参数后缀建立输入索引；
4. 获取当前启用的 `BizIndicator` 实例；
5. 分别调用四类设备计算器；
6. 将计算尝试分为成功、缺失输入和非法输入；
7. 先持久化正式结果或异常审计；
8. 持久化成功后更新 Redis 并推送 WebSocket。

引擎不直接包含数学公式。

### 3.2 纯公式计算器

公式计算器不访问 MySQL、TDengine、Redis 或 WebSocket：

- `ChillerCopCalculator`
- `CoolingTowerEfficiencyCalculator`
- `PumpEfficiencyCalculator`
- `AhuPowerEfficiencyCalculator`
- `WetBulbTemperatureCalculator`

每个计算器接收强类型输入并返回统一计算结果，保证数学逻辑能够独立测试和版本化。

### 3.3 统一计算结果

计算结果至少表达：

- 状态：`SUCCESS`、`MISSING_INPUT`、`INVALID_INPUT`；
- 最终指标值；
- 实际参与计算的输入；
- 输入单位与质量等级；
- 中间计算步骤；
- 公式编码和版本；
- 输入选择方式；
- 缺失输入列表；
- 结构化原因编码；
- 面向前端的安全说明。

中间结果仅属于计算过程，不注册成独立性能指标：

- 冷机：`Q₀`、`Nᵢ` 和功率来源；
- 冷却塔：直接或派生 `TWB`；
- 水泵：`ΔH`；
- AHU：由百分数转换后的 `ηt`。

## 4. 输入身份与隔离

公式输入不得只按 `suffixCode` 查找。完整身份至少包含：

```text
buildingId + equipId + componentCode + suffixCode
```

建筑级环境测点没有 `equipId`，按以下身份匹配：

```text
buildingId + familyCode + componentCode + suffixCode
```

### 4.1 冷机与水泵同名后缀

内部标准命名会在不同设备和组件上复用 `GW`、`PPE` 后缀：

| 用途 | 标准测点 | 组件身份 | 代码变量 |
|---|---|---|---|
| 冷机制冷量流量 | `WCR1_GW` | `MAIN/GW` | `chillerWaterFlowM3h` |
| 冷机输入功率 | `WCR1_PPE` | `MAIN/PPE` | `chillerInputPowerKw` |
| 水泵流量 | `WCR1_Pc_GW` | `Pc/GW` | `pumpWaterFlowM3h` |
| 水泵输入功率 | `WCR1_Pc_PPE` | `Pc/PPE` | `pumpInputPowerKw` |

外部 MQTT 别名映射为：

```text
WCR1_Flow   → WCR1_GW
WCR1_PPE    → WCR1_PPE
PUMP1_Flow  → WCR1_Pc_GW
PUMP1_Power → WCR1_Pc_PPE
```

公式引擎使用内部标准身份，不解析或硬编码外部 MQTT 别名。

## 5. 公式定义

### 5.1 冷水机组制冷量与 COP

输入：

- `MAIN/TWin`：冷冻水进水温度，℃；
- `MAIN/TWout`：冷冻水出水温度，℃；
- `MAIN/GW`：冷冻水流量，m³/h；
- `MAIN/PPE`：机组输入功率，kW；
- 备用 `MAIN/Voltage`、`MAIN/Current`、`MAIN/PF`。

公式 5-1：

```text
Q₀ = GW × ρ × c × (TWin − TWout) / 3600

ρ = 1000 kg/m³
c = 4.18 kJ/(kg·℃)
```

输入功率两级策略：

```text
优先：Nᵢ = PPE
兜底：Nᵢ = Voltage × Current × PF × √3 / 1000
```

公式 5-2：

```text
COP = Q₀ / Nᵢ
```

有效性要求：

- `GW > 0`；
- `TWin > TWout`；
- `Nᵢ > 0`；
- 备用路径中 `Voltage > 0`、`Current > 0`、`0 < PF ≤ 1`；
- 所有输入和结果必须为有限数。

PPE 有效时必须优先使用 PPE，且备用电参数不参与本次质量等级计算。
备用电参数只在 PPE 缺失时启用。PPE 已上报但值为零、负数或非有限数时，
该分钟返回 `INVALID_INPUT`，不得切换到备用路径掩盖主功率测点异常。

黄金数据：

```text
GW=100m³/h，TWin=12℃，TWout=7℃，PPE=100kW
Q₀=580.555556kW
COP=5.805556
```

### 5.2 冷却塔效率

输入：

- `CT/TWin`：冷却水进水温度，℃；
- `CT/TWout`：冷却水出水温度，℃；
- `CT/TWB`：室外湿球温度，℃；
- 兜底 `DBO/ENV/TDB` 与 `RHO/ENV/RH`。

公式 5-4：

```text
ηic = (TWin − TWout) / (TWin − TWB) × 100%
```

湿球温度策略：

1. 同一分钟存在有效 `CT/TWB` 时使用直接测量值；
2. `CT/TWB` 缺失时，使用同一分钟干球温度和相对湿度；
3. 派生计算采用 ASHRAE 湿空气关系和数值求解；
4. 大气压力默认 `101.325kPa`，通过配置覆盖；
5. 本期修改默认压力或湿球算法时必须提升公式版本；
6. 计算过程标记 `DIRECT` 或 `DERIVED`。

派生路径只处理直接 `TWB` 缺失。直接 `TWB` 已上报但违反物理关系或不是
有限数时返回 `INVALID_INPUT`，不得用派生值掩盖直接传感器异常。

有效性要求：

- `TWin > TWout`；
- `TWin > TWB`；
- 直接或派生 `TWB` 必须为有限数；
- `0 ≤ ηic ≤ 100`；
- 相对湿度必须满足 `0 < RH ≤ 100`。

黄金数据：

```text
TWin=35℃，TWout=30℃，TWB=25℃
ηic=50%
```

### 5.3 水泵效率

输入：

- `Pc/GW`：水泵流量，m³/h；
- `Pc/Pout`：出口压力，Pa；
- `Pc/Pin`：入口压力，Pa；
- `Pc/Z`：进出口压力表高度差，m；
- `Pc/PPE`：水泵输入功率，kW。

公式 5-6：

```text
ΔH = (Pout − Pin) / (ρ × g)

ρ = 1000 kg/m³
g = 9.8 m/s²
```

公式 5-5：

```text
η = GW × ρ × g × (ΔH + Z) × 10⁻⁶ / (3.6 × PPE) × 100%
```

有效性要求：

- `GW > 0`；
- `Pout > Pin`；
- `PPE > 0`；
- `ΔH + Z > 0`；
- `0 ≤ η ≤ 100`；
- 所有输入和结果必须为有限数。

黄金数据：

```text
GW=100m³/h，Pout=300000Pa，Pin=100000Pa，Z=1m，PPE=10kW
ΔH=20.408163m
η=58.277778%
```

### 5.4 AHU 单位风量耗功值

输入：

- `MAIN/TotalPress`：风机全压，Pa；
- `MAIN/EtaT`：风机总效率，数据库单位为 `%`。

公式 5-7：

```text
ηt = EtaT / 100
Ws = TotalPress / (3600 × ηt)
```

输出单位统一为 `W/(m³/h)`。

有效性要求：

- `TotalPress > 0`；
- `0 < EtaT ≤ 100`；
- 所有输入和结果必须为有限数。

黄金数据：

```text
TotalPress=1000Pa，EtaT=60%
Ws=0.462963W/(m³/h)
```

## 6. 质量传播与数值精度

指标质量等于本次实际参与计算的输入质量最大值：

```text
indicatorQuality = max(usedInputQualities)
```

规则：

- 常量不提高质量等级；
- PPE 路径不统计备用电参数质量；
- 电参数兜底路径统计 `Voltage`、`Current` 和 `PF`；
- 直接湿球路径不统计 `TDB/RH`；
- 派生湿球路径统计 `TDB/RH`；
- 非法输入不能通过提高质量等级转成可用值；
- 质量 `2` 表示数据来源为典型值，不表示公式异常。

计算和存储使用 `double` 完整精度，不在公式层提前四舍五入。前端展示时按指标保留 2～3 位小数。

## 7. 结果存储

### 7.1 轻量成功指标表

扩展现有 `st_indicator_minute`，仅保存成功结果：

```text
ts               TIMESTAMP   来源分钟 minuteStart
val              DOUBLE      指标值
data_quality     TINYINT      结果质量
formula_version  NCHAR(32)    公式版本
calculated_at    TIMESTAMP    实际计算时间
```

保留现有标签：

```text
indicator_id
indicator_code
building_id
system_group_id
equip_id
```

主表不保存完整输入 JSON、缺失项或逐步计算文本，避免扩大热查询行宽。

### 7.2 异常审计表

新增 `st_formula_calc_exception`，只记录未产生指标的计算尝试：

```text
ts               TIMESTAMP
calc_status      NCHAR(32)
reason_code      NCHAR(64)
missing_inputs   NCHAR(512)
formula_version  NCHAR(32)
calculated_at    TIMESTAMP
```

标签与指标实例一致：

```text
indicator_id
indicator_code
building_id
system_group_id
equip_id
```

允许的 `calc_status`：

- `MISSING_INPUT`
- `INVALID_INPUT`
- `ENGINE_ERROR`

`reason_code` 使用稳定英文编码，`missing_inputs` 使用内部标准组件/后缀，不保存 SQL、连接信息或异常堆栈。

### 7.3 幂等规则

成功指标和异常审计均以：

```text
indicatorId + minuteStart
```

作为逻辑唯一身份。仓储必须使用来源分钟 `minuteStart`，不得使用 `System.currentTimeMillis()` 作为指标时间。

相同分钟重复处理必须得到确定结果。恢复后由失败转成功时：

1. 成功指标表写入该分钟正式结果；
2. 原异常审计保留为历史证据；
3. 详情查询发现同分钟已有成功指标时以成功结果为准；
4. 不产生重复趋势时间点。

## 8. Redis 与 WebSocket

Redis 保存最新完整计算状态，替代当前 `"value|timestamp"` 字符串：

```json
{
  "indicatorCode": "WCR_COP",
  "minuteStart": 1785112200000,
  "status": "SUCCESS",
  "value": 5.805556,
  "dataQuality": 0,
  "formulaVersion": "WCR_COP_V1",
  "inputs": [],
  "steps": []
}
```

缺失或非法输入同样覆盖最新缓存状态，避免前端继续展示上一分钟旧值。
缓存更新必须比较 `minuteStart`：只有新状态的来源分钟不早于当前缓存时才允许
覆盖。旧分钟恢复补算成功后写入正式历史，但不得把 Redis 最新状态或前端大屏
回退到较早分钟。

Redis 保持旁路缓存语义：

- TDengine 成功后才更新；
- 写入失败只记录警告；
- Redis 不可用不影响正式指标；
- 查询未命中时回退 TDengine。

WebSocket 统一发送 `HVAC_INDICATOR` 消息，包含：

- 建筑、指标和设备身份；
- 来源分钟；
- 计算状态；
- 成功值和质量；
- 失败原因和缺失输入。

WebSocket 失败不回滚 TDengine 结果。

## 9. 正常计算与恢复

### 9.1 正常事件

正常冻结事件携带本次已经成功写入 `st_raw_minute` 的分钟结果。公式引擎直接使用 `aggregates`，不再次查询 TDengine。

### 9.2 恢复事件

`recovery=true` 的事件可能只包含本次补齐的部分测点。公式引擎必须按建筑和 `minuteStart` 回查完整冻结分钟输入，再执行公式，不能只使用事件局部数据。

### 9.3 指标缺口补算

分钟聚合恢复只能发现原始分钟缺口，无法发现公式监听器或指标写入失败。因此新增独立低频补算：

- 默认检查最近 10 分钟；
- 窗口可配置；
- 查找启用指标实例缺少的成功结果；
- 读取对应完整分钟输入；
- 使用相同公式入口重算；
- 依赖指标时间戳幂等；
- 本期单实例串行执行；
- 多实例分布式锁留待扩展阶段。

## 10. 固定写入顺序与失败隔离

固定顺序：

```text
计算
→ TDengine 正式指标或异常审计成功落库
→ Redis 最佳努力更新
→ WebSocket 最佳努力推送
```

规则：

- 一个设备公式失败不影响其他设备；
- 一个指标缺失不影响同一分钟其他指标；
- 纯公式未知异常转换为 `ENGINE_ERROR`；
- TDengine 正式指标写入失败时禁止发送成功消息；
- TDengine 整体不可用且异常审计也无法写入时保留结构化应用日志；
- 低频补算负责恢复未落库指标；
- 历史趋势只读取成功指标；
- 缺失和异常不转换为零值。

## 11. 查询接口

新增：

```http
GET /api/hvac/buildings/{buildingId}/indicators/latest
GET /api/hvac/indicators/{indicatorId}/history?from={from}&to={to}
GET /api/hvac/indicators/{indicatorId}/calculations/{minuteStart}
```

### 11.1 最新指标

返回当前建筑启用指标的最新计算状态：

- `SUCCESS`
- `MISSING_INPUT`
- `INVALID_INPUT`
- `NO_DATA`

Redis 优先，未命中时回退 TDengine。返回非成功状态时不得附带上一分钟旧值。
TDengine 回退查询需要比较最新成功指标和最新异常审计的来源分钟，以时间较新的
计算尝试决定当前状态；同一分钟同时存在历史异常和恢复后的成功结果时优先成功。

### 11.2 指标历史

- 只返回成功指标；
- 使用来源分钟作为时间轴；
- 不用零值填充失败分钟；
- 沿用最大 31 天查询范围；
- 按指标实例和建筑权限隔离。

### 11.3 公式过程

成功指标详情通过以下数据重建：

```text
指标 minuteStart + formulaVersion
→ 查询同一分钟 st_raw_minute
→ 调用对应版本计算器 explain
→ 返回公式、参数、单位、中间值、质量和结果
```

最新详情优先使用 Redis 中的完整计算过程。历史重建使用长期保留的分钟输入。

失败分钟返回异常审计中的状态、原因和缺失输入。

## 12. 权限

内部指标接口允许：

- `BUILDING_OWNER`
- `ENERGY_MANAGER`
- `PLATFORM_ADMIN`

规则：

- 建筑业主和能效管理方必须拥有目标建筑权限；
- 平台管理员拥有全部建筑；
- `THIRD_PARTY` 不调用内部指标接口，继续使用独立 `/open-api/**` 边界；
- 权限必须由服务端执行。

## 13. 测试设计

### 13.1 纯公式黄金测试

- 冷机公式 5-1、5-2；
- PPE 优先；
- 电参数完整兜底；
- PPE 已存在但非法时不启用电参数兜底；
- 冷却塔公式 5-4；
- ASHRAE 派生湿球温度参考案例；
- 直接湿球已存在但非法时不启用派生兜底；
- 水泵公式 5-6、5-5；
- AHU 百分数归一；
- 所有本设计列出的黄金数值。

### 13.2 边界测试

- 每一个必需输入缺失；
- PPE 和备用功率输入同时缺失；
- 非有限数；
- 零或负流量；
- 冷机反向温差；
- 零或负功率；
- 非法功率因数；
- 冷却塔非法温度关系；
- 非法相对湿度；
- 水泵反向压差；
- 水泵效率超过 100%；
- AHU 效率为百分数而非小数；
- 实际输入路径的最差质量透传；
- 跨设备、跨建筑数据隔离。

### 13.3 引擎测试

- 正常事件不查询 TDengine；
- 恢复事件读取完整分钟；
- 四个指标独立成功或跳过；
- 缺失项和原因编码稳定；
- 一个计算器失败不阻断其他指标；
- `isForCalc=0` 的测点不能进入公式；
- 同一事件重复处理结果一致。

### 13.4 仓储与恢复测试

- 使用 `minuteStart`；
- 成功指标轻量写入；
- 异常只写审计表；
- 相同指标和分钟保持幂等；
- TDengine 写入异常向上传播；
- 正式写入失败后不更新缓存；
- 低频补算恢复缺口；
- 恢复成功后不产生重复趋势行；
- Schema 初始化和增量迁移均包含新列与异常审计表。

### 13.5 Redis、WebSocket 与 API 测试

- Redis 成功结果序列化；
- 缺失状态覆盖旧成功状态；
- 旧分钟恢复结果不能覆盖更新分钟的缓存状态；
- Redis 故障不影响正式写入；
- WebSocket 成功与失败消息契约；
- 最新、历史、计算详情响应；
- 历史趋势不包含失败零值；
- 三类内部角色和建筑范围权限；
- 第三方角色被内部接口拒绝。

### 13.6 回归和实际链路

- 运行公式定向测试；
- 运行完整 `mvn test`；
- 现有 77 个后端测试不得回归；
- 使用 Docker Compose 启动 MySQL、TDengine、Redis、EMQX；
- 发送 19 测点 MQTT 数据；
- 等待自然分钟冻结；
- 验证四类指标、缓存、WebSocket 和公式详情；
- 不修改仓库中用户已有的 PPT 文件。

## 14. 验收标准

1. 同一分钟输入完整时，四类启用指标均可独立计算。
2. 冷机正确执行 PPE 优先与三相电参数兜底。
3. 冷却塔正确执行直接湿球优先与 ASHRAE 派生兜底。
4. 水泵严格区分 `Pc/GW`、`Pc/PPE` 与冷机 `MAIN/GW`、`MAIN/PPE`。
5. AHU 总效率在计算前由百分数转换为小数。
6. 缺失或非法输入不产生伪造指标值，并能返回明确原因。
7. 成功结果使用来源分钟幂等写入轻量指标表。
8. 异常信息与成功趋势分离存储。
9. 指标质量只由实际参与计算的输入决定。
10. TDengine 成功后才更新 Redis 和 WebSocket。
11. 正常事件无额外分钟查询，恢复和补算能够取得完整输入。
12. 最新、历史和计算详情接口受角色与建筑权限保护。
13. 公式过程可以展示输入、单位、中间值、质量、版本和最终结果。
14. 全量自动化测试通过，实际 19 测点链路可完成一次端到端计算。

## 15. 后续阶段

核心公式闭环稳定后，再依次建设：

1. 质量等级 1 的相邻分钟插值；
2. 质量等级 2 的典型值生成；
3. 默认值来源、审批、启停和有效期；
4. 数据补全人工审核；
5. 多建筑并行计算与分布式锁；
6. 系统级 COP 和联合调适。
