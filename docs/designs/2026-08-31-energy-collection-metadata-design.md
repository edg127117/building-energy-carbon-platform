# 能源采集元数据完善设计

## 1. 状态与权威边界

本文是“数据源与采集策略治理”之后的能源测点专业属性候选设计，实施范围仅限后端数据库、
服务、API、权限、审计和自动化测试。实现和隔离自动化验证完成后仍属于活动候选；真实能源
采集、现场验收和能源专家确认必须另行取得证据。

本设计保留 [`数据源与采集策略治理闭环设计`](2026-08-24-collection-source-policy-governance-design.md)
的上报周期、迟到时间、保留策略、审核和运行快照边界，不修改该冻结设计。

## 2. 目标与非目标

标准测点需要独立表达：能源类型、电力来源、数据性质、业务统计周期、年度汇总要求以及专业
确认状态。标准单位继续以 `biz_data_point.unit` 为唯一权威；用能系统继续使用既有
`system_group_id` 和关系治理，不在新表重复保存。

本候选不实现 Vue 页面、单位换算、月度聚合、碳因子和碳排计算、结算凭证、金额折算、计量
分摊、南向协议、真实设备连接或采样周期下发。

## 3. 数据模型

新增 `biz_energy_point_profile`：

| 字段 | 语义 |
|---|---|
| `profile_id` | 平台内部稳定主键 |
| `point_id`、`building_id` | 标准测点和建筑组合外键；一测点最多一条属性 |
| `energy_type` | `ELECTRICITY`、`NATURAL_GAS`、`HEAT`、`COLD`、`FUEL` |
| `energy_subtype` | 电力来源；非电力必须为空 |
| `value_semantics` | `INSTANTANEOUS`、`CUMULATIVE`、`PERIOD_TOTAL` |
| `reporting_period` | 首版只接受 `MONTH` |
| `annual_summary` | 是否要求全年汇总 |
| `confirmation_status` | `PENDING_EXPERT` 或 `CONFIRMED` |
| `evidence_reference` | 本次配置依据，禁止为空 |
| `config_revision` | 从 0 开始的乐观锁修订号 |
| `create_by`、`update_by` | 创建人与最近修改人 |
| `create_time`、`update_time` | MySQL 毫秒时间事实 |

电力来源首版只接受：

- `GRID_PURCHASED`；
- `TRADED_PURCHASED`；
- `DIRECT_RENEWABLE`；
- `SELF_GENERATED`。

`PENDING_EXPERT` 的电力测点允许来源为空，以免软件补造专业分类；`CONFIRMED` 的电力测点
必须填写上述来源。非电力测点填写电力来源时稳定拒绝。新表不保存 `unit`、
`system_group_id`、采集周期或保留期限。

只有单位属于当前资料明确给出的 `kW`、`kWh`、`m³`、`Nm³`、`GJ`、`t` 的标准测点才能
创建能源属性。首版不推断更细的能源类型与单位组合，也不执行单位换算。

迁移只创建空表，不为继承的 19 个 HVAC 测点自动生成能源属性；没有专业资料时保持“无属性”，
不得把测试值写入生产迁移。

## 4. API 与 DTO

统一使用 `/v1`、`Result<T>`、稳定 `PageResponse<T>` 和独立 DTO：

```text
GET  /v1/energy-point-profiles
GET  /v1/energy-point-profiles/{profileId}
POST /v1/energy-point-profiles
PUT  /v1/energy-point-profiles/{profileId}
GET  /v1/energy-point-profiles/options
GET  /v1/energy-point-profiles/collection-context
```

列表必须传 `buildingId`，可以按 `pointId` 进一步过滤。创建请求携带 `buildingId` 和
`pointId`，服务端验证两者归属；更新不能改变测点或建筑，并要求 `expectedRevision`。

选项接口返回能源类型、电力来源、数据性质、统计周期、确认状态和当前支持的标准单位，供后续
前端生成选择项，不把展示文案当作数据库事实。

采集上下文通过 `sourceId` 和 `aliasId` 查询并返回：数据源编码、现场点码、平台测点编码、
能源属性、标准单位、业务统计周期、当前活动或草稿策略的期望上报周期和允许迟到时间，以及
专业确认状态。能源属性尚未建立时返回 `profilePresent=false` 和空专业字段，不阻断现有 19 点。
`DEVICE_EVENT_TIME` 继续表示事件时间来源，不替代 `valueSemantics`。

## 5. 权限、事务、并发和审计

- `BUILDING_OWNER`、`ENERGY_MANAGER`、`PLATFORM_ADMIN` 可以读取授权建筑；
- `ENERGY_MANAGER`、`PLATFORM_ADMIN` 可以创建和修改授权建筑的能源测点属性；
- 第三方角色无权访问；所有操作在服务端重新校验建筑范围；
- 创建和修改均锁定标准测点，修改使用 `profile_id + config_revision` 条件更新；
- 重复创建、测点不存在、建筑不一致和过期修订号分别返回稳定机器错误码；
- 业务变更与采集域 `biz_collection_config_audit_log` 证据在同一 MySQL 事务提交，
  并通过既有统一审计查询入口检索；不把业务事实复制到安全事件表；
- 审计摘要只记录专业枚举、修订号和“是否提供依据”，不复制依据正文。

## 6. 与采集策略的边界

能源属性修改不更新 `expected_interval_seconds`、`allowed_delay_seconds`、采集策略版本、
`config_revision/runtime_revision` 或运行快照。采集上下文只读关联当前策略，证明“每月统计”
不会被解释成“每月采集一次”。

## 7. 验证边界

自动化至少覆盖：

1. 电力累计电量与天然气累计量正向创建；
2. 非电力填写电力来源、建筑不一致、测点不存在、重复创建拒绝；
3. 乐观锁冲突、跨建筑访问和第三方访问拒绝；
4. `PENDING_EXPERT` 与 `CONFIRMED`、依据和统一审计准确；
5. 月度统计不改变现有采集策略周期；
6. 无能源属性的继承 19 点继续可运行；
7. H2 业务测试、Flyway 迁移契约和隔离 MySQL 8 完整链验证。

普通自动化测试不得连接真实 MQTT、TDengine、Redis、现场设备或第三方服务。隔离 MySQL 验证
只证明结构和事务语义，不等于生产迁移、真实能源采集或现场验收。
