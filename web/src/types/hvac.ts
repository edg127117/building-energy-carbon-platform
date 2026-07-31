/**
 * HVAC 页面使用的后端 DTO。
 *
 * 这些类型只描述现有建筑、分钟快照和最新指标接口，不混入颜色、图标或布局，
 * 以免接口协议与大屏展示实现互相耦合。
 */
export type PageResult<T> = {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export type Building = {
  buildingId: string
  buildingName: string
  buildingCode: string
  buildingType: string | null
  climateZone: string | null
}

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

export type HvacSnapshotResponse = {
  buildingId: string
  generatedAt: number
  points: SnapshotPoint[]
}

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

export type HvacLatestResponse = {
  buildingId: string
  generatedAt: number
  indicators: LatestIndicator[]
}
