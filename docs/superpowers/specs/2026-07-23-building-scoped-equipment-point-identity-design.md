# 多建筑工业资产与测点身份模型改造设计

## 目标

一次性解决当前模型中设备、系统分组、测点和指标编码被误当成平台全局主键的问题，同时消除《冷热源系统测点规范及性能指标开发交底文档》与《设计冻结书 V1.0-19测点》之间的命名冲突。

最终模型必须同时做到：

1. 平台内部身份稳定、全局唯一，不随现场改名而改变；
2. 资产按建筑、系统、设备、部件、测点形成清晰层级；
3. 标准测点编码遵循统一命名规范；
4. 当前冻结书、PLC、厂家 API 的已有编码通过别名继续接入；
5. MySQL、MQTT、TDengine、分钟聚合和未来 COP 使用同一身份模型；
6. 本次只修改后端、数据库脚本和对接文档，不开发前端页面。

## 设计依据

### 项目规范

交底文档规定：

- 标准测点采用“设备类别前缀_子设备/部件_参数后缀”结构；
- 设备大类为 `WCR[n]`、`Bh[n]`、`Bs[n]`；
- 冷水一次泵、冷却塔、冷却水泵分别使用 `Pc`、`CT`、`Pcd` 部件代码；
- 环境测点为 `DBO`、`RHO`。

冻结书中的 `TOWER1_*`、`PUMP1_*`、`AHU1_*`、`DBO_TDB`、`DBO_RH` 作为试点现场和既有接口编码保留，但不再直接作为内部标准身份。

### 工业建模原则

- 采用层级资产模型：建筑 → 系统分组 → 物理设备 → 测点；
- 技术 ID 与现场可读名称分离；
- 标准名称与来源系统地址分离；
- 公式通过测点语义和内部 ID 取数，不解析字符串猜测设备含义；
- 命名规则有版本和来源，扩展类型不能伪装成原标准已有内容。

## 已比较方案

### 方案一：完全沿用冻结书编码

继续把 `TOWER1_TCWin`、`PUMP1_Flow` 当成标准测点编码。改动最少，但会把冻结书对统一规范的临时扩展永久固化，无法解释 `WCR[n]_CT_*`、`WCR[n]_Pc_*` 等正式规则。

### 方案二：立即替换全部现场编码

要求 MQTT、PLC 和数据库马上只使用统一规范编码。内部最整齐，但会破坏现有19点验收数据和后续厂家协议兼容。

### 方案三：标准编码与来源别名分离（采用）

内部保存标准测点编码，现场已有编码保存为别名。采集入口先解析别名得到 `point_id`，之后所有存储和计算只使用稳定内部身份。该方案兼顾规范、兼容性和未来多厂家接入。

## 四层身份模型

### 第一层：平台内部 ID

下列字段是平台技术身份，由后端使用 MyBatis-Plus `IdType.ASSIGN_ID` 生成：

```text
building_id
system_group_id
equip_id
point_id
indicator_id
```

内部 ID 创建后不可修改，不作为现场展示编号，也不要求设备端上报。

### 第二层：现场资产编码

现场可读编码用于铭牌、运维和设备台账，例如：

```text
WCR1
TOWER1
PUMP1
AHU1
```

资产编码只要求在建筑内唯一。不同建筑可以分别存在 `WCR1`。

### 第三层：标准测点编码

标准测点编码遵循交底文档的层级结构，例如：

```text
WCR1_TWin
WCR1_PPE
WCR1_Pc_PPE
WCR1_CT_PPE
WCR1_Pcd_PPE
Bh1_Ph_PPE
Bs1_Ph_PPE
DBO
RHO
```

标准编码是运维和数据治理字段，不是数据库主键。

### 第四层：来源别名

现场协议或冻结书编码通过别名映射到标准测点，例如：

```text
TOWER1_TCWin → 对应标准冷却塔进水温度测点
PUMP1_Flow   → 对应标准冷水一次泵流量测点
DBO_TDB      → DBO
DBO_RH       → RHO
```

一个标准测点允许存在多个来源别名，以支持 MQTT、Modbus、BACnet、厂家 API 等不同接入源。

## MySQL 数据模型

### `biz_equipment_type`

该表只管理物理资产分类和资产编号前缀，不作为标准测点命名规则：

```text
type_code          VARCHAR(20)  主键，例如 WCR、WCT、WCP、AHU、Bh、Bs
type_name          VARCHAR(100)
asset_code_prefix  VARCHAR(20)  例如 WCR、TOWER、PUMP、AHU、Bh、Bs
equip_category     VARCHAR(50)
standard_source    VARCHAR(32)  HANDOFF 或 FREEZE_EXTENSION
status             TINYINT
```

试点字典：

```text
WCR / WCR    / CHILLER / HANDOFF
WCT / TOWER  / TOWER   / FREEZE_EXTENSION
WCP / PUMP   / PUMP    / FREEZE_EXTENSION
AHU / AHU    / AHU     / FREEZE_EXTENSION
Bh  / Bh     / BOILER  / HANDOFF
Bs  / Bs     / BOILER  / HANDOFF
```

`WCT → TOWER` 和 `WCP → PUMP` 只说明物理设备台账如何编号，不声明它们是交底文档的测点前缀。

### `biz_point_naming_rule`

该表保存正式测点命名模板：

```text
rule_id            VARCHAR(32)  全局 ID
standard_version   VARCHAR(32)  例如 HANDOFF_V1
family_code        VARCHAR(20)  WCR、Bh、Bs、AHU
component_code     VARCHAR(20)  MAIN、Pc、CT、Pcd、Ph
code_template      VARCHAR(100) WCR[n]、WCR[n]_Pc
standard_source    VARCHAR(32)  HANDOFF 或 FREEZE_EXTENSION
status             TINYINT
```

基础规则：

```text
WCR / MAIN / WCR[n]      / HANDOFF
WCR / Pc   / WCR[n]_Pc   / HANDOFF
WCR / CT   / WCR[n]_CT   / HANDOFF
WCR / Pcd  / WCR[n]_Pcd  / HANDOFF
Bh  / MAIN / Bh[n]       / HANDOFF
Bh  / Ph   / Bh[n]_Ph    / HANDOFF
Bs  / MAIN / Bs[n]       / HANDOFF
Bs  / Ph   / Bs[n]_Ph    / HANDOFF
DBO / ENV  / DBO         / HANDOFF
RHO / ENV  / RHO         / HANDOFF
AHU / MAIN / AHU[n]      / FREEZE_EXTENSION
```

AHU 明确属于冻结书扩展规则，不能标记为原交底规范。

### `biz_system_group`

```text
system_group_id    VARCHAR(32)  主键，全局 ID
system_group_code  VARCHAR(50)  建筑内业务编码，例如 SG001
building_id        VARCHAR(32)
```

唯一约束：

```sql
UNIQUE KEY uk_system_group_building_code
    (building_id, system_group_code)
```

原来的 `SG001` 从主键迁移为 `system_group_code`。

### `biz_space`

`space_id` 已经是全局内部 ID，保留现状。为业务编码补充：

```sql
UNIQUE KEY uk_space_building_code
    (building_id, space_code)
```

`space_code` 允许为空；非空时在建筑内不得重复。

顶级空间的 `parent_space_id` 从字符串 `"0"` 改为 `NULL`。子空间使用真实内部 `space_id`，并通过同建筑复合外键防止跨建筑挂接。

### `biz_equipment`

```text
equip_id          VARCHAR(32)  主键，全局 ID
equip_code        VARCHAR(50)  现场资产编码，例如 TOWER1
type_code         VARCHAR(20)  关联 biz_equipment_type
system_group_id   VARCHAR(32)  内部系统分组 ID
building_id       VARCHAR(32)
space_id          VARCHAR(32)
```

唯一约束：

```sql
UNIQUE KEY uk_equipment_building_code
    (building_id, equip_code)
```

创建新设备时，服务端根据 `biz_equipment_type.asset_code_prefix` 查询该建筑、该类型包含逻辑删除记录在内的历史最大序号并加一。

```text
BLD001 / WCR → WCR1、WCR2
BLD002 / WCR → WCR1、WCR2
```

逻辑删除后不复用编号。并发创建由唯一索引裁决，冲突后重新读取最大编号并有限重试。

### `biz_data_point`

```text
point_id             VARCHAR(32)   主键，全局 ID
point_code           VARCHAR(100)  建筑内标准测点编码
point_name           VARCHAR(100)
building_id          VARCHAR(32)
system_group_id      VARCHAR(32)   可空
equip_id             VARCHAR(32)   物理设备 ID；环境测点为空
naming_rule_id       VARCHAR(32)
family_code          VARCHAR(20)
component_code       VARCHAR(20)
suffix_code          VARCHAR(20)
```

唯一约束：

```sql
UNIQUE KEY uk_data_point_building_code
    (building_id, point_code)
```

普通设备测点创建时必须验证：

- 设备存在且未删除；
- 设备、系统分组、空间和测点属于同一建筑；
- 命名规则存在且启用；
- 标准测点编码符合所选模板；
- 标准编码在该建筑内未被占用。

环境测点允许 `equip_id = NULL`，但必须携带 `building_id`。

### `biz_point_alias`

```text
alias_id           VARCHAR(32)   主键，全局 ID
building_id        VARCHAR(32)
source_system      VARCHAR(50)   例如 MQTT_FREEZE_V1、MODBUS_GATEWAY_A
source_point_code  VARCHAR(100)  来源编码
point_id           VARCHAR(32)   标准测点内部 ID
status             TINYINT
```

唯一约束：

```sql
UNIQUE KEY uk_point_alias_source
    (building_id, source_system, source_point_code)
```

同一来源地址只能映射到一个标准测点；一个标准测点可以有多个来源别名。

### `biz_indicator`

为未来 COP 和效率结果建立稳定身份：

```text
indicator_id      VARCHAR(32)  主键，全局 ID
building_id       VARCHAR(32)
indicator_code    VARCHAR(100) 标准指标编码
scope_type        VARCHAR(20)  EQUIPMENT、SYSTEM、BUILDING
scope_id          VARCHAR(32)  对应 equip_id、system_group_id 或 building_id
equip_id          VARCHAR(32)  可空
system_group_id   VARCHAR(32)  可空
status            TINYINT
```

唯一约束：

```sql
UNIQUE KEY uk_indicator_scope
    (building_id, indicator_code, scope_type, scope_id)
```

`indicator_id` 决定 TDengine 指标子表身份，避免不同建筑的 `WCR_COP` 冲突。

### `control_commands`

`command_id` 注释声明全局唯一，因此补充：

```sql
UNIQUE KEY uk_control_command_id (command_id)
```

同时为 `device_id` 增加普通索引，支持设备指令历史查询。

### 关系完整性

MySQL 配置数据量低、写入频率低，适合使用真实外键约束。所有物理外键采用 `ON UPDATE RESTRICT ON DELETE RESTRICT`；业务删除继续使用 `del_flag`，不通过级联物理删除历史配置。

为防止只校验 ID 存在却关联到另一栋建筑，建立同建筑复合候选键及复合外键：

```text
biz_space(parent_space_id, building_id)
    → biz_space(space_id, building_id)

biz_equipment(system_group_id, building_id)
    → biz_system_group(system_group_id, building_id)

biz_equipment(space_id, building_id)
    → biz_space(space_id, building_id)

biz_data_point(system_group_id, building_id)
    → biz_system_group(system_group_id, building_id)

biz_data_point(equip_id, building_id)
    → biz_equipment(equip_id, building_id)

biz_point_alias(point_id, building_id)
    → biz_data_point(point_id, building_id)

biz_indicator(system_group_id, building_id)
    → biz_system_group(system_group_id, building_id)

biz_indicator(equip_id, building_id)
    → biz_equipment(equip_id, building_id)
```

`biz_equipment.type_code` 外键关联 `biz_equipment_type.type_code`，`biz_data_point.naming_rule_id` 外键关联 `biz_point_naming_rule.rule_id`。可空关联在为空时不触发外键校验。

服务层仍保留同样的建筑一致性检查，用于返回清晰业务错误；数据库外键作为最后一道完整性保护。

## 19测点兼容策略

当前冻结书编码保留为 `MQTT_FREEZE_V1` 来源别名；标准测点使用交底规则或明确的扩展规则。

完整19点映射：

```text
WCR1_TWin        → WCR1_TWin
WCR1_TWout       → WCR1_TWout
WCR1_Flow        → WCR1_GW
WCR1_PPE         → WCR1_PPE
WCR1_Voltage     → WCR1_Voltage       (FREEZE_EXTENSION)
WCR1_Current     → WCR1_Current       (FREEZE_EXTENSION)
WCR1_PF          → WCR1_PF            (FREEZE_EXTENSION)
TOWER1_TCWin     → WCR1_CT_TWin
TOWER1_TCWout    → WCR1_CT_TWout
TOWER1_TWB       → WCR1_CT_TWB        (FREEZE_EXTENSION)
PUMP1_Flow       → WCR1_Pc_GW
PUMP1_Pout       → WCR1_Pc_Pout       (FREEZE_EXTENSION)
PUMP1_Pin        → WCR1_Pc_Pin        (FREEZE_EXTENSION)
PUMP1_Z          → WCR1_Pc_Z          (FREEZE_EXTENSION)
PUMP1_Power      → WCR1_Pc_PPE
AHU1_TotalPress  → AHU1_TotalPress    (FREEZE_EXTENSION)
AHU1_EtaT        → AHU1_EtaT          (FREEZE_EXTENSION)
DBO_TDB          → DBO
DBO_RH           → RHO
```

冻结书中没有定义水泵属于 `Pc` 还是 `Pcd` 的数据，仅凭 `PUMP1` 无法推断。试点拓扑将其描述为冷冻水泵，因此初始化映射采用 `Pc`；后续新增水泵必须显式选择部件角色，不允许后端通过名称猜测。

冻结书新增但交底文档未定义的参数，如 `Voltage`、`Current`、`PF`、`Pout`、`Pin`、`Z`、`TotalPress`、`EtaT`，按 `FREEZE_EXTENSION` 规则登记，不伪装为 `HANDOFF`。

## MQTT 协议与别名解析

HVAC MQTT 报文为：

```json
{
  "buildingId": "BLD001",
  "deviceId": "PUMP1",
  "pointCode": "PUMP1_Flow",
  "val": 35.2,
  "timestamp": 1784781000000
}
```

当前主题默认使用来源系统 `MQTT_FREEZE_V1`。未来不同网关使用独立主题或接入配置确定 `source_system`，不信任设备自行声明来源系统。

采集校验顺序：

1. 校验 `buildingId`、`deviceId`、`pointCode`、`val`、`timestamp`；
2. 使用 `buildingId + sourceSystem + pointCode` 查询别名；
3. 取得标准 `point_id` 和完整测点配置；
4. 普通设备测点校验 `deviceId == equip_code`；
5. 环境测点跳过设备编码相等校验，但仍按建筑隔离；
6. 执行状态、时间戳、数值范围和冻结边界校验；
7. 后续事件同时携带内部 ID、标准编码和来源编码。

采集热路径缓存键为：

```java
PointAliasKey(buildingId, sourceSystem, sourcePointCode)
```

缓存值包含标准测点、物理设备和系统分组信息，避免每条 MQTT 报文查询 MySQL。

## TDengine 模型

### 原始事件与分钟汇总

子表名：

```text
st_raw_event_{pointId}
st_raw_minute_{pointId}
```

原始事件的数据列除时间、数值和质量字段外，还保存：

```text
source_system
source_point_code
source_device_id
```

来源信息必须是普通数据列，不能是 TDengine Tag。因为同一个标准测点可以由多个来源别名上报，而同一子表的 Tag 不能逐行变化。

两个超级表的固定身份标签：

```text
point_id
point_code
building_id
system_group_id
equip_id
equip_code
family_code
component_code
suffix_code
is_for_calc
```

原始事件幂等键：

```text
point_id + event_time
```

分钟汇总幂等键：

```text
point_id + minute_start
```

### 指标结果

指标子表名：

```text
st_indicator_minute_{indicatorId}
```

指标标签：

```text
indicator_id
indicator_code
building_id
scope_type
scope_id
system_group_id
equip_id
```

不同建筑或不同作用域的同名指标拥有不同 `indicator_id`，不会写入同一子表。

## 分钟聚合与公式边界

分钟聚合统一按 `point_id` 分组：

```text
原始事件窗口查询
  → 按 point_id 分组
  → 通过 point_id 取得当前有效配置
  → 生成 RawMinuteAggregate
  → 写入 st_raw_minute_{pointId}
```

恢复任务读取某分钟已存在的 `point_id` 集合，不再使用 `point_code`。

`RawTelemetryEvent` 携带来源系统、来源测点编码和来源设备编码，供原始证据复审。`RawMinuteAggregate` 和冻结事件只携带稳定标准身份及语义：

```text
point_id
point_code
building_id
system_group_id
equip_id
family_code
component_code
suffix_code
```

未来 COP 公式通过这些语义字段选择输入，不解析 `TOWER1_TCWin` 等字符串。

## 后端管理接口

接口路径中的资源标识全部使用内部 ID：

```text
GET    /equipment/detail/{equipId}
PUT    /equipment/update
DELETE /equipment/delete/{equipId}

GET    /datapoint/equip/{equipId}
PUT    /datapoint/update
DELETE /datapoint/delete/{pointId}
```

设备创建请求不接受 `equipId` 和 `equipCode`，由后端生成。测点创建请求不接受 `pointId`，但必须指定标准编码、命名规则和关联资产。

管理接口提供来源别名 CRUD，但只允许 `PLATFORM_ADMIN` 修改。所有资源查询继续执行建筑权限校验。

## 数据迁移

### MySQL

1. 预检 `building_id + 旧编码` 冲突，发现冲突立即停止；
2. 创建资产类型、命名规则、测点别名和指标表；
3. 为系统分组生成内部 ID，把原 `SG001` 迁移为业务编码；
4. 为设备生成内部 ID，把原设备 ID 迁移为 `equip_code`；
5. 为测点生成 `point_id`，把现有编码转换为标准编码；
6. 写入 `MQTT_FREEZE_V1` 别名，保留原19点编码；
7. 更新设备、测点、指标之间的内部 ID 关联；
8. 把顶级空间的 `parent_space_id='0'` 转换为 `NULL`；
9. 建立复合唯一索引、候选键、外键和必要普通索引；
10. 校验行数、关联完整性和19点映射后提交。

迁移不自动物理删除旧记录。正式执行前必须备份数据库。

### TDengine

1. 为三个超级表增加新的内部身份和语义标签；
2. 为已有子表回填 `point_id`、`indicator_id` 等标签；
3. 新代码切换为内部 ID 子表名；
4. 旧子表继续保留在超级表下，历史查询仍可访问；
5. 验证新旧历史后再停止旧写入，不删除历史子表。

## 兼容边界

- 新 HVAC 报文必须包含 `buildingId`；
- 普通电表链路使用全局物理 `device_id`，保持现状；
- `pointCode` 在 MQTT 中表示来源编码，进入内部链路后转换为标准测点；
- 前端本次不修改，后续必须展示 `equipCode`、`pointCode`，不能把内部 ID 当作业务名称；
- COP 尚未实现，本次只建立正确指标身份和冻结事件契约；
- 不实现插值、长期缺失补值、质量等级2或模拟数据。

## 错误处理

- 别名不存在或停用：拒绝报文，不写 TDengine；
- 别名指向无效测点：拒绝报文并记录配置错误；
- `deviceId` 与物理设备编码不一致：拒绝报文；
- 创建资产发生编号并发冲突：重新读取最大编号并有限重试；
- 标准编码或来源别名重复：返回明确复合唯一冲突；
- 配置缓存刷新失败：继续使用最后一份完整缓存；
- TDengine 建表或写入失败：不确认 MQTT QoS 1 消息；
- 分钟批量写入失败：不发布冻结事件、不推进水位。

## 测试与验收

- 两栋建筑可分别拥有 `WCR1`、`TOWER1`、`PUMP1`，内部 ID 均不同；
- 同一建筑同类设备连续创建编号递增，逻辑删除后不复用；
- 系统分组、空间、设备和标准测点的建筑内业务编码不能重复；
- 一个标准测点可以绑定多个来源别名；
- 同一来源地址不能映射到两个标准测点；
- 19个冻结书编码全部能映射到正确标准测点；
- `PUMP1` 初始化映射明确为 `Pc`，不会错误映射为 `Pcd`；
- MQTT 缺少 `buildingId` 或别名不存在时被拒绝；
- 两栋建筑上报同名来源编码时分别命中正确 `point_id`；
- TDengine 为同名业务测点生成不同内部 ID 子表；
- 分钟聚合按 `point_id` 分组，不跨建筑混算；
- 恢复任务按 `point_id` 判断缺口；
- 不同建筑的 `WCR_COP` 使用不同指标子表；
- `command_id` 重复写入被数据库拒绝；
- 完整 Maven 测试和打包通过。
