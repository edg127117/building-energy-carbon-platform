import {
  FROZEN_POINT_DEFINITIONS,
  INDICATOR_DEFINITIONS,
} from './hvacDashboard'
import type {
  HvacIndicatorTrendResponse,
  HvacPointHistoryResponse,
  SnapshotPoint,
  TrendRecord,
} from '@/types/hvac'

export type HvacHistoryMode = 'indicators' | 'points'
export type HvacHistoryPreset = '1h' | '6h' | '24h' | '7d' | 'custom'

export type HvacHistoryPointOption = {
  pointId: string
  pointCode: string
  label: string
  unit: string
  precision: number
}

export type HvacTrendPoint = {
  time: number
  average: number | null
  minimum: number | null
  maximum: number | null
  sampleCount: number
  dataQuality: number | null
}

export type HvacTrendSeries = {
  id: string
  code: string
  label: string
  unit: string
  precision: number
  points: HvacTrendPoint[]
}

export type HvacTrendGroup = {
  unit: string
  series: HvacTrendSeries[]
}

const MINUTE_MS = 60_000
const MAX_HISTORY_SPAN_MS = 31 * 24 * 60 * MINUTE_MS

/**
 * 在用户触发查询时把结束时间落到当前整分钟，再计算固定快捷跨度。
 * 这使前后端都以自然分钟和半开区间 {@code [from,to)} 解释同一次查询。
 */
export function presetRange(
  preset: Exclude<HvacHistoryPreset, 'custom'>,
  now = Date.now(),
): { from: number; to: number } {
  const durations: Record<Exclude<HvacHistoryPreset, 'custom'>, number> = {
    '1h': 60 * MINUTE_MS,
    '6h': 6 * 60 * MINUTE_MS,
    '24h': 24 * 60 * MINUTE_MS,
    '7d': 7 * 24 * 60 * MINUTE_MS,
  }
  const to = Math.floor(now / MINUTE_MS) * MINUTE_MS
  return { from: to - durations[preset], to }
}

/**
 * 在发请求前校验自定义时间；后端仍会重复校验，前端提示不能替代安全边界。
 */
export function validateHistoryRange(
  from: number | null,
  to: number | null,
): string | null {
  if (from === null || to === null) {
    return '请选择完整的开始和结束时间'
  }
  if (!Number.isFinite(from) || !Number.isFinite(to)) {
    return '时间范围无效'
  }
  if (from >= to) {
    return '开始时间必须早于结束时间'
  }
  if (to - from > MAX_HISTORY_SPAN_MS) {
    return '查询跨度不能超过31天'
  }
  return null
}

/**
 * 从当前建筑快照建立可选测点，只接受冻结的 19 个标准编码并保留后端内部 ID。
 * 冻结定义提供稳定顺序、业务短名称和精度，快照只证明该测点当前仍属于可查询范围。
 */
export function buildHistoryPointOptions(
  points: SnapshotPoint[],
): HvacHistoryPointOption[] {
  const sourceByCode = new Map<string, SnapshotPoint>()
  for (const point of points) {
    if (!sourceByCode.has(point.pointCode)) {
      sourceByCode.set(point.pointCode, point)
    }
  }
  return FROZEN_POINT_DEFINITIONS.flatMap((definition) => {
    const point = sourceByCode.get(definition.internalCode)
    if (!point?.pointId) return []
    return [{
      pointId: point.pointId,
      pointCode: point.pointCode,
      label: definition.label,
      unit: point.unit || definition.unit,
      precision: definition.precision,
    }]
  })
}

/**
 * 把批量指标图表 DTO 转成按工程单位分组的统一模型。
 * 名称、单位和精度取自四项稳定定义，数值统计与质量完全保留后端事实。
 */
export function adaptIndicatorTrends(
  response: HvacIndicatorTrendResponse,
): HvacTrendGroup[] {
  const definitions = new Map(
    INDICATOR_DEFINITIONS.map((definition) => [
      definition.indicatorCode,
      definition,
    ]),
  )
  const series = response.series.flatMap((source) => {
    const definition = definitions.get(source.indicatorCode)
    if (!definition) return []
    return [{
      id: source.indicatorId,
      code: source.indicatorCode,
      label: definition.label,
      unit: definition.unit,
      precision: definition.precision,
      points: trendPoints(source.records, response.resolutionMinutes),
    }]
  })
  return groupByUnit(series)
}

/**
 * 把当前建筑已选测点历史转成统一模型；只接纳现有选项中的 ID，防止跨建筑响应混入。
 * 名称和单位优先使用后端序列元数据，冻结选项继续提供稳定精度。
 */
export function adaptPointTrends(
  response: HvacPointHistoryResponse,
  options: HvacHistoryPointOption[],
): HvacTrendGroup[] {
  const optionById = new Map(options.map((option) => [option.pointId, option]))
  const series = response.series.flatMap((source) => {
    const option = optionById.get(source.pointId)
    if (!option) return []
    return [{
      id: source.pointId,
      code: source.pointCode,
      label: source.pointName || option.label,
      unit: source.unit || option.unit,
      precision: option.precision,
      points: trendPoints(source.records, response.resolutionMinutes),
    }]
  })
  return groupByUnit(series)
}

/** 将后端质量等级转成图表 Tooltip 文案，未知数字保留原值而不伪装成 Q0。 */
export function historyQualityLabel(quality: number | null): string {
  if (quality === 0) return 'Q0 真实数据'
  if (quality === 1) return 'Q1 线性插值'
  if (quality === 2) return 'Q2 典型值'
  if (quality === null) return '无数据'
  return `Q${quality} 未知质量`
}

/**
 * 保留服务端记录并在跨桶处插入一个空分隔点，供 ECharts 断线。
 * 这里只标记缺口，不补齐完整时间轴、不插值，也不重新计算平均值。
 */
function trendPoints(
  records: TrendRecord[],
  resolutionMinutes: number,
): HvacTrendPoint[] {
  const interval = resolutionMinutes * MINUTE_MS
  const ordered = [...records].sort((left, right) => left.time - right.time)
  const points: HvacTrendPoint[] = []
  for (const record of ordered) {
    const previous = points.at(-1)
    if (previous && record.time - previous.time > interval) {
      points.push({
        time: previous.time + interval,
        average: null,
        minimum: null,
        maximum: null,
        sampleCount: 0,
        dataQuality: null,
      })
    }
    points.push({
      time: record.time,
      average: record.average,
      minimum: record.minimum,
      maximum: record.maximum,
      sampleCount: record.sampleCount,
      dataQuality: record.dataQuality,
    })
  }
  return points
}

/** 按系列首次出现的单位建立独立图表组，同单位系列保留原响应顺序。 */
function groupByUnit(series: HvacTrendSeries[]): HvacTrendGroup[] {
  const groups = new Map<string, HvacTrendSeries[]>()
  for (const item of series) {
    const grouped = groups.get(item.unit) ?? []
    grouped.push(item)
    groups.set(item.unit, grouped)
  }
  return [...groups].map(([unit, grouped]) => ({ unit, series: grouped }))
}
