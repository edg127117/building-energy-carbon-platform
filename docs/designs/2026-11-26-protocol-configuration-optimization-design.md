# 设备报文接入优化（力度 A）最终方案（已对齐后端评审）

- **任务定位**：独立体验优化任务（与第一、二阶段待接入设备主链路解耦，基于最新 `origin/main` 建独立任务分支与 PR 交付）
- **优化力度**：**力度 A**（轻量后端 3 项减负，免 DDL 迁移 + 前端 5 步收拢为 3 步全中文向导）
- **协作分工**：
  - **后端实施方**：实施 3 项后端接口与校验优化（免数据库 DDL 迁移）、后端自动化测试、联调审查与共享测试部署
  - **前端实施方**：实施 `web/src/modules/protocol-configuration/` 3 步重构、全中文文案、令牌合规与 Vitest 测试

---

## 1. 目标与边界

### 1.1 本轮解决的核心问题

1. **草稿无法半途暂存**：当前创建/更新草稿即要求 `sourceTopic`、`identityPath` 和全部必填测点映射完整，配置中途无法保存。
2. **草稿不可检索、不可清理**：列表无搜索/筛选条件，且废弃草稿无法删除。
3. **发布步骤过碎**：一次普通发布需手工完成「保存草稿 → 手动冻结版本 → 手动发布预览 → 创建 `DRAFT` 审批单 → 再提交审批」4–5 步，且第 5 步「设备」并无后端流程。
4. **前端界面噪音大**：第 1 步默认筛选草稿态产品（常规已启用产品搜不到）、暴露无意义空分页（`共 0 条` / `共 1 条`）、内嵌重复的产品创建工作区、顶部常驻黄色免责说明条、报文映射与试解析被拆到两个页面来回切换。

### 1.2 明确不做的范围（Scope Out）

- **不修改「产品与测点模板」模块**（`/configuration/ingestion/products` 页面、流程及后端产品 API 均保持不变）；仅从报文接入页面移除重复内嵌的产品工作区与散落按钮，保留轻量跳转入口。
- **不修改运行时适配器拉取与回执契约**、MQTT 采集入库链路、以及一/二期待接入设备主流程。
- **不放宽任何安全与运行底线**（见第 2 节）。

---

## 2. 不可放松的安全与架构底线

1. **权限边界**：协议配置与发布所有接口仍仅限平台管理员（`PLATFORM_ADMIN`）。
2. **精确 Topic 约束（分阶段校验）**：
   - **步骤 2 试解析（`preview`）**：校验配置完整且 `sourceTopic` 为无通配符（`+`/`#`）、无控制字符、无首尾空白的精确 Topic（此阶段尚未选定目标适配器，不查目标授权）；
   - **步骤 3 发布预览、冻结与发布提交（`draft-preview` / `freeze` / `draft-publish-requests`）**：除上述精确 Topic 校验外，在选定目标适配器后进一步校验 `sourceTopic` 位于该目标的 `allowedTopics` 授权范围内。
3. **不可变冻结版本与产品防漂移**：冻结版本（`biz_protocol_version`）仍记录 `productHash` 与内容摘要 `SHA-256`；关联产品模板变更后，发布前必须基于最新草稿重新校验/冻结。
4. **原始单位显式确认**：前端与后端均**不得**从样例 JSON 数值自动猜测 `sourceUnit`，必须由实施人员显式确认原始单位后方可通过试解析与发布。
5. **目标在线与独立审核**：发布前仍校验适配器在线（`contactTimeout`）及乐观序号 `expectedSequence`；发布仍经 `SensitiveChange`（`PUBLISH_PROTOCOL_CONFIGURATION`）独立审核与职责分离（生产禁自审）。
6. **增量保留与显式回退**：普通发布仍按 `profileCode` 基于目标**当前已发布配置**（当前发布序号对应的完整配置集合）进行合并并自动保留其余协议；移除协议仍仅允许通过显式历史回退。
7. **运行可靠性元数据保留**：`ProtocolDraftService.update` 对 V2 / 周期量高级字段（`seqPath`、`messageIdPath`、`bootIdPath`、`batchIdPath`、`retransmittedAtPath`、`maxAckMode`、`correlationPolicy` 及映射 `valueType`）的未传保留机制必须继续保持，防止普通编辑静默降级运行契约。

---

## 3. 后端 3 项优化与共享契约（已确认对齐）

> [!NOTE]
> `biz_protocol_draft` 表结构为 `(draft_id, revision, configuration_json, updated_at, updated_by)`。以下 B1、B2、B3 全部在现有表结构上实现，**无需新增数据库 DDL 迁移**。

### 3.1 项 B1：两级草稿校验 + 试解析可定位错误契约

- **涉及接口**：`POST /v1/protocol-configurations`、`PUT /v1/protocol-configurations/{id}`（暂存级）；`POST /v1/protocol-configurations/preview`、`POST /v1/protocol-deployments/versions/{draftId}`、`POST /v1/protocol-deployments/draft-preview`、`POST /v1/protocol-deployments/draft-publish-requests`（完整级）。
- **两级校验规则**：
  1. **草稿暂存级校验（`create` / `update`）**：
     - **必填**：`name`（非空，≤ 100）、`productId`（产品存在）、`profileCode` 与 `identityType`（与所选产品一致）。
     - **允许为空 / 未完成**：`sourceTopic` 可为空；`identityPath` 可为空；`mappings` 可为空或 `[]`（`null` 归一化为 `[]`，≤ 128 条）；已添加映射行允许尚未填写 `sourcePath` 或 `sourceUnit`。
     - **已填项即校验格式**：
       - 若 `sourceTopic` 非空 → 校验无 `+`/`#`、无控制字符、无首尾空白；
       - 若 `identityPath` / `timestampPath` / `discriminatorPath` / V2 可靠性路径非空 → 校验合法 JSON Pointer；`discriminatorPath` 与 `discriminatorValue` 仍需成对填写；
       - 对每条映射：`metricCode` 非空且不可重复；若 `sourcePath` 非空 → 校验合法 JSON Pointer 且不可重复；若 `targetUnit` 非空且非周期参数 → 须与产品对应启用测点一致；若填写 `scale`/`offset` → 须满足数字精度约束。
     - **空安全保留**：`ProtocolDraftService.update` 在保留既有 V2 元数据及映射 `valueType` 时，对 `mappings` 为空或 `sourcePath` 为空做好空指针防护（使用 `Objects.equals`）。
  2. **完整契约校验（`preview` / `freeze` / `draft-preview` / `draft-publish-requests`）**：
     - 保持现有全部强校验：非空精确 `sourceTopic`、非空 `identityPath`、非空 `mappings` 且至少启用 1 条、覆盖产品全部必填启用测点、每条有效映射均含合法 `sourcePath` / `sourceUnit` / `targetUnit` / `scale` / `offset`、`IntervalMappings.validate` 通过；发布预览与发布提交阶段叠加产品 `ENABLED`、`allowedTopics` 与 `SnapshotValidator` 校验。
- **试解析可定位错误字段约定（前后端对齐）**：
  - 放宽 `Configuration` / `Mapping` 的 Bean Validation 后，`POST /v1/protocol-configurations/preview` 遇到配置不完整或格式错误（如缺 `sourceTopic`、缺 `identityPath`、某测点缺 `sourcePath` 或 `sourceUnit`、必填测点未映射等）时，**不依赖全局 `MethodArgumentNotValidException` 通用 400 文案**，而是优先返回 `200` + `Preview(success=false, errors=[PreviewError(code, path, message), ...])`（复用既有 `PreviewError` 结构，无需改响应模型）。
  - `PreviewError.path` 统一使用以下可定位标识（前端据此精准高亮对应输入框或映射行）：
    - **顶层配置字段**：`sourceTopic`、`identityPath`、`timestampPath`、`discriminatorPath`、`discriminatorValue`、`mappings`
    - **具体映射行及字段**：`mappings.<metricCode>`（如必填测点未映射 / 整行问题）、`mappings.<metricCode>.sourcePath`、`mappings.<metricCode>.sourceUnit`、`mappings.<metricCode>.targetUnit`、`mappings.<metricCode>.scale`、`mappings.<metricCode>.offset`
    - **样例报文 JSON Pointer**（沿用现有运行时解析报错）：以 `/` 开头的路径（如 `/data/temp`）

### 3.2 项 B2：草稿检索与废弃草稿安全删除

1. **先筛选、再分页**：
   - `GET /v1/protocol-configurations?page=1&size=20&keyword=&productId=`
   - `keyword`（可选）：忽略大小写模糊匹配草稿配置中的 `name` 或 `profileCode`；
   - `productId`（可选）：按草稿关联的 `productId` 精确筛选；
   - **列表查询与总数统计必须使用同一套筛选条件**，先筛选再按 `page` / `size` 分页，保持现有 `PageResponse<Detail>` 格式与约束（`page >= 1`，`1 <= size <= 100`）。
2. **废弃草稿安全删除（与冻结共用草稿行锁）**：
   - `DELETE /v1/protocol-configurations/{id}`（仅 `PLATFORM_ADMIN`）
   - 由于 `biz_protocol_version` 无外键，**删除（`delete`）与冻结（`freeze`）均在同一事务内对 `biz_protocol_draft` 的目标草稿行加行锁（`SELECT ... FOR UPDATE`）**，保证对同一草稿的并发删除与冻结严格串行，无需改表：
     - 草稿不存在 → 返回 `404`（`PROTOCOL_NOT_FOUND`）；
     - 持锁状态下查 `biz_protocol_version` 存在 `draft_id = id` → 拒绝删除并返回 `409`（错误码 `PROTOCOL_DRAFT_REFERENCED`，提示「该草稿已生成冻结版本，不可删除」）；
     - 未被引用 → 删除草稿并记录审计 `PROTOCOL_DRAFT_DELETE`，返回 `Result.success(null)`。

### 3.3 项 B3：草稿一步发布预览与冻结提交审批（4 步合 1 步）

保留现有全部底层发布接口（`/versions/{draftId}`、`/versions`、`/preview`、`/requests`、`/rollback-preview`、`/rollback-requests`、`/targets`、`/import`、`/targets/{id}/history*`）兼容，新增 2 个面向草稿的编排接口：

1. **只读发布差异预览**：`POST /v1/protocol-deployments/draft-preview`
   - **请求体（`DraftPreviewRequest`）**：
     ```json
     {
       "draftId": "string",
       "revision": 3,
       "targetId": "string",
       "expectedSequence": 2
     }
     ```
   - **行为**：
     - 校验 `PLATFORM_ADMIN`、草稿存在且 `draft.revision == revision`（冲突返 `409`）、完整契约校验通过、关联产品处于 `ENABLED`、目标适配器存在且在线（`contactTimeout`）且序号等于 `expectedSequence`；
     - 在内存中构造候选协议条目并与目标**当前已发布配置**（当前发布序号对应的完整配置集合）按 `profileCode` 合并（保留其余协议），执行 `allowedTopics` 与 `SnapshotValidator` 校验；
     - **不写库、不写入 `biz_protocol_version`、不创建审批单**，直接返回既有 `PublicationPreview`（含 `ADDED` / `REPLACED` / `RETAINED` 差异列表）；
     - **已对齐占位约定**：当 `(draftId, revision)` 尚未冻结时，`afterVersion.versionId` 固定返回 `"DRAFT_PREVIEW"`，前端仅作展示，正式提交仍传 `draftId` 与 `revision`。

2. **一步冻结并提交发布申请**：`POST /v1/protocol-deployments/draft-publish-requests`
   - **请求体（`DraftPublishRequest`）**：
     ```json
     {
       "draftId": "string",
       "revision": 3,
       "targetId": "string",
       "expectedSequence": 2,
       "idempotencyKey": "string (<=100)"
     }
     ```
   - **行为（冻结并送审在同一事务内完成）**：
     1. **先查幂等已有申请**：同一提交人以相同 `idempotencyKey` 重试时，优先查询已有 `PUBLISH_PROTOCOL_CONFIGURATION` 申请；若存在且关键参数（`draftId`、`revision`、`targetId`、`expectedSequence` 等）一致，直接幂等返回该记录（若仍处 `DRAFT` 则顺带推进至 `PENDING_REVIEW`），避免首次提交并执行后目标序号推进导致重试被序号校验误拦；若同一 `idempotencyKey` 被复用于不同参数则明确拒绝（`409`）；
     2. 在同一事务内：对草稿行加锁校验修订号与完整契约 → 幂等执行 `freeze(draftId, revision)`（若产品模板在旧冻结后发生漂移，明确拒绝提示重新保存草稿后再发）；
     3. 调用现有 `prepare(new PublishRequest(targetId, expectedSequence, List.of(version.versionId()), idempotencyKey), roles)` 基于目标当前已发布配置合并并校验完整快照与 `allowedTopics`；
     4. 调用 `changes.createDraft(userId, "PUBLISH_PROTOCOL_CONFIGURATION", mapper.valueToTree(command), idempotencyKey)`，并立即调用 `changes.submit(...)` 推进至 `PENDING_REVIEW`；
     5. 返回 `SensitiveChangeRecord`。

3. **旧回退接口保持兼容（已对齐）**：
   - `POST /v1/protocol-deployments/rollback-requests` **保持现有行为返回 `DRAFT`**，回退流程在前端「适配器与历史」抽屉中继续通过现有 `ChangeRequestControl` 提交审批；普通发布走新接口 `draft-publish-requests`，一次点击直接进入 `PENDING_REVIEW`。

---

## 4. 前端 3 步重构方案

改造范围收敛在 `web/src/modules/protocol-configuration/`，遵循平台 `--bec-*` 令牌规范（通过 `web/scripts/check-foundation.mjs`）、全中文文案（`locales/zh-CN.ts`）与 `1440 / 1280 / 768 / 390px` 响应式布局。

### 4.1 顶部工作区与次要功能收纳

- **精简页头**：标题「设备报文接入」+ 当前草稿名称与状态胶囊（`未保存` / `草稿` / `试解析通过` / `待审核` / `已加载`）；彻底移除常驻黄色 `draftNotice` 警示条。
- **顶部工具栏 3 个清晰入口**：
  1. **「草稿箱」抽屉**：支持关键词检索、按产品筛选、新建空白草稿、一键切换草稿、以及删除未冻结废弃草稿（已冻结草稿按后端 `409` 给出明确中文提示）。
  2. **「适配器与历史」抽屉**：无损保留现有全部低频运维能力，不占用主向导版面：
     - 适配器目标列表与「登记适配器目标」（保留一次性密钥展示与复制）；
     - 目标历史发布记录、单次不可变版本集合详情与「回退预览 / 申请回退」（继续复用 `ChangeRequestControl` 完成 `DRAFT → 提交审核`）；
     - 历史完整协议与禁用归档导入（`/import`）。
  3. **「产品与测点模板」外链按钮**：直达 `/configuration/ingestion/products`，替代原内嵌的重复 `ProductWorkspace` 与零散「新建产品草稿」按钮。

### 4.2 三步主向导设计

| 步骤 | 页面名称 | 合并/替代的原步骤 | 核心体验与关键交互 |
| :--- | :--- | :--- | :--- |
| **步骤 1** | **基础配置与产品** | 原步骤 1（大幅瘦身） | ① 产品列表默认仅查「已启用」产品；② 仅在总页数 > 1 时显示分页条（消灭 `共 0 条`/`共 1 条` 空分页）；③ 选中产品自动带出只读中文标签与启用测点预览；④ 填写配置名称、精确 MQTT 订阅主题（支持从在线适配器授权 Topic 快速点选填充）、设备身份路径，时间戳与判别条件折叠进「高级识别设置」；⑤ 任意时刻可点「保存草稿」暂存。 |
| **步骤 2** | **报文映射与试解析** | 原步骤 2 + 步骤 3（合并同屏） | ① 左栏粘贴设备样例 JSON，点击「提取字段」生成可点击字段树；② 右栏按产品启用测点呈现映射表（突出必填/可选标记，保留「原始单位须人工确认」底线，系数/偏移/排序收在行内高级区）；③ 同屏嵌入「立即试解析」，即时展示解析出的设备身份、时间来源与各测点转换结果；④ 按 `PreviewError.path`（顶层字段名、`mappings.<metricCode>.*` 或 JSON Pointer）直接高亮并定位到对应输入框或映射行，修改后就地复测。 |
| **步骤 3** | **发布与加载状态** | 原步骤 4 + 吸收原步骤 5 | ① 选择目标适配器（展示在线/离线状态、当前序号、授权 Topic）；② 选定在线目标后自动触发 `draft-preview`，清晰展示相对「当前已发布配置」的 **新增 / 更新 / 保留** 中文变更差异（`DRAFT_PREVIEW` 仅作预览展示，摘要哈希等技术细节收进折叠区）；③ 点击一次 **「提交发布申请」** 调 `draft-publish-requests` 一步冻结并送审；④ 同页嵌入 `ChangeRequestControl` 查看独立审核状态，执行后按 `5s` 间隔（上限 `120s`）轮询加载回执直至「已加载」；⑤ 加载成功后同页提供直达按钮 **「前往待接入设备」**（`/configuration/ingestion/pendingDevices`），取消多余的第 5 步。 |

### 4.3 界面语言与视觉规范

- **主视觉全中文化**：状态、变更类型（`ADDED`→新增、`REPLACED`→更新、`RETAINED`→保留、`REMOVED`→移除）、身份类型（`DEVICE_CODE`→设备编码、`MAC`→MAC 地址、`SN`→序列号等）、时间来源均以中文标签展示；协议编码、JSON Pointer 路径、SHA-256 摘要等技术字段作为弱化辅助信息或折叠技术详情展示。
- **令牌与响应式**：禁止任何裸 `px`/`rem`/硬编码色值/模板裸文本节点，全部走 `--bec-*` 令牌与 `zh-CN.ts`；在 `1440 / 1280 / 768 / 390px` 下双栏自动折行且无横向溢出。

---

## 5. 验收标准

| 维度 | 验收项 | 预期结果 |
| :--- | :--- | :--- |
| **B1 草稿暂存** | 仅填写名称与已启用产品（不填 Topic、身份路径与映射）保存草稿 | 保存成功（`200`）；切换草稿后再编辑更新，既有 V2/周期量元数据不丢失。 |
| **B1 强校验与错误定位** | 缺原始单位、缺映射路径、未映射必填测点或 Topic 含 `+`/`#` 时触发试解析；选定目标后 Topic 超出授权时触发发布预览/提交 | 试解析返回 `Preview(success=false)` 且 `PreviewError.path` 精准指向顶层字段或 `mappings.<metricCode>.*`，前端高亮定位；步骤 3 目标授权越界被后端准确拦截。 |
| **B2 检索与删除** | 按关键词、产品筛选草稿并翻页；删除未冻结草稿；尝试删除已冻结草稿；并发删除与冻结同一草稿 | 先筛选再分页，列表与总数一致；未冻结草稿成功删除并记审计；已冻结草稿返回 `409 PROTOCOL_DRAFT_REFERENCED`；草稿行锁确保不会产生孤儿冻结版本。 |
| **B3 一步发布闭环** | 步骤 3 选定在线目标适配器 → 查看差异预览 → 点一次「提交发布申请」→ 幂等重试 → 独立审核执行 → 适配器加载回执 | 预览不写库，未冻结版本显示 `DRAFT_PREVIEW` 且相对「当前已发布配置」展示 `ADDED/REPLACED/RETAINED`；一次点击同事务冻结并生成 `PENDING_REVIEW` 审批单；同参数同幂等键重试（即使已执行且序号推进）直接返回原记录；审核执行后适配器回执 `LOADED`，原有其他协议自动保留；页面提供「前往待接入设备」快捷入口。 |
| **前端体验与规范** | 第 1 步默认产品、分页显隐、全中文、无常驻黄条、多视口 | 默认仅显示已启用产品；≤ 1 页不展示分页条；`npm run lint`（含 foundation 检查）、`npm run check`、`npm run test:run`、`npm run build` 全部通过；`1440/1280/768/390px` 布局无错位溢出。 |
| **非回归** | 目标适配器登记（一次性密钥）、历史协议导入、历史发布查看与回退预览/回退申请（保持返回 `DRAFT`） | 全部在「适配器与历史」抽屉中完整可用，既有底层接口兼容无回归。 |
