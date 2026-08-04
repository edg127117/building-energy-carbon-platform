/**
 * HVAC 页面使用的后端 DTO。
 *
 * 这些类型只描述现有建筑、分钟快照、最新指标和计算详情接口，不混入颜色、图标或布局，
 * 以免接口协议与大屏展示实现互相耦合。
 */
export type PageResult<T> = {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** `/building/list` 返回的建筑选择字段，数据源是 MySQL 建筑档案。 */
export type Building = {
  buildingId: string
  buildingName: string
  buildingCode: string
  buildingType: string | null
  climateZone: string | null
}

/**
 * `/hvac/buildings/{buildingId}/snapshot` 中的单测点分钟快照。
 *
 * `minute` 是该测点最新冻结分钟，不要求与其他测点相同；`NO_DATA` 时数值、质量和
 * 分钟为空，`sampleCount` 为 0，页面必须保留空槽位而不能填入模拟值。
 */
export type SnapshotPoint = {
  pointId: string
  pointCode: string
  pointName: string
  equipId: string | null
  equipCode: string | null
  unit: string | null
  minute: number | null
  average: number | null
  minimum: number | null
  maximum: number | null
  sampleCount: number
  dataQuality: number | null
  status: 'NORMAL' | 'NO_DATA' | string
}

/** 建筑快照响应；`generatedAt` 是响应组装时间，不是所有测点共同的数据时间。 */
export type HvacSnapshotResponse = {
  buildingId: string
  generatedAt: number
  points: SnapshotPoint[]
}

/**
 * `/hvac/buildings/{buildingId}/indicators/latest` 中的单指标状态。
 * 只有 `SUCCESS` 携带有效值和质量；失败状态通过 `reasonCode`、`missingInputs` 解释。
 */
export type LatestIndicator = {
  indicatorId: string
  indicatorCode: string
  equipId: string | null
  minuteStart: number | null
  status:
    | 'SUCCESS'
    | 'MISSING_INPUT'
    | 'INVALID_INPUT'
    | 'ENGINE_ERROR'
    | 'NO_DATA'
    | string
  value: number | null
  unit: string | null
  dataQuality: number | null
  formulaVersion: string | null
  reasonCode: string | null
  missingInputs: string[]
}

/** 建筑全部活动指标的最新响应，页面再按四个稳定编码筛选展示。 */
export type HvacLatestResponse = {
  buildingId: string
  generatedAt: number
  indicators: LatestIndicator[]
}

/** 计算详情中的一个分钟输入，质量等级沿用后端 Q0/Q1/Q2 语义。 */
export type HvacCalculationInput = {
  key: string
  pointId: string
  pointCode: string
  value: number
  unit: string | null
  dataQuality: number
}

/** 后端公式引擎返回的一个有序计算步骤；表达式只展示，不在浏览器求值。 */
export type HvacCalculationStep = {
  code: string
  expression: string
  value: number
  unit: string | null
}

/**
 * `/hvac/indicators/{indicatorId}/calculations/{minuteStart}` 返回的计算证据。
 * 成功状态包含值、质量、版本、输入和步骤；失败状态保留审计原因和缺失语义键，
 * 页面不得用默认值补齐可空字段。
 */
export type HvacCalculationDetail = {
  indicatorId: string
  indicatorCode: string
  equipId: string
  minuteStart: number
  status:
    | 'SUCCESS'
    | 'MISSING_INPUT'
    | 'INVALID_INPUT'
    | 'ENGINE_ERROR'
    | 'NO_DATA'
    | string
  value: number | null
  unit: string | null
  dataQuality: number | null
  formulaVersion: string | null
  inputs: HvacCalculationInput[]
  steps: HvacCalculationStep[]
  reasonCode: string | null
  missingInputs: string[]
}
