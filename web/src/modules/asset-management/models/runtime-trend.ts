import type { AssetPointTrendSeries } from './assets'
import { ledgerPointDisplay } from './point-display'

const minuteMs = 60_000
type TrendData = AssetPointTrendSeries['data']

function gapLimit(data: TrendData): number {
  const intervals = data.slice(1).map(([time], index) => time - data[index]![0])
    .filter(interval => interval > 0)
    .sort((a, b) => a - b)
  const median = intervals[Math.floor(intervals.length / 2)] ?? minuteMs
  // 历史接口只返回可展示的分钟桶。用稳定的上报间隔区分正常留白与缺口，稀疏或不规则数据按分钟保守断线。
  const regular = intervals.filter(interval => Math.abs(interval - median) <= median / 4).length
  const cadence = regular >= 3 && regular >= intervals.length * 0.6 ? median : minuteMs
  return cadence * 1.5
}

export function presentRuntimeTrend(series: AssetPointTrendSeries | null, equipmentCategory: string | null | undefined) {
  const ordered = (series?.data ?? []).filter(([time]) => Number.isFinite(time))
    .map(([time, value]): TrendData[number] => [time, value != null && Number.isFinite(value) ? value : null])
    .sort(([a], [b]) => a - b)
  const valid = ordered.filter((row): row is [number, number] => row[1] != null)
  const { unit, multiplier } = ledgerPointDisplay(series?.unit, equipmentCategory)
  const limit = gapLimit(valid)
  const data: TrendData = []
  for (const [time, value] of ordered) {
    const previous = data.at(-1)
    if (previous && previous[1] != null && value != null && time - previous[0] > limit) {
      data.push([previous[0] + (time - previous[0]) / 2, null])
    }
    data.push([time, value == null ? null : value * multiplier])
  }
  return {
    data,
    unit,
    lastValidAt: valid.at(-1)?.[0] ?? null,
    hasGaps: data.some(([, value]) => value == null),
  }
}

type TrendAxis = { min: number; max: number; interval: number; formatter?: (value: number) => string }
type TrendPoint = { pointCode?: string; category?: string | null }

export function peopleTrendAxis(data: TrendData): TrendAxis | null {
  const counts = data.flatMap(([, value]) => value == null ? [] : [value])
  if (counts.length === 0) return null
  const peak = Math.max(...counts)
  // 人数从零起算。至少显示五人范围，避免单人变化占据半张图；扩展时给峰值留出空间。
  const upper = Math.max(5, peak + Math.ceil(peak / 5))
  const step = upper / 5
  const magnitude = 10 ** Math.floor(Math.log10(step))
  const interval = [1, 2, 2.5, 5, 10].map(factor => factor * magnitude)
    .find(size => Number.isInteger(size) && size >= step) ?? 10 * magnitude
  return { min: 0, max: Math.ceil(upper / interval) * interval, interval }
}

export function runtimeTrendAxis(data: TrendData, unit: string, point: TrendPoint = {}) {
  const values = data.flatMap(([, value]) => value == null ? [] : [value])
  if (values.length === 0) return null
  const low = Math.min(...values)
  const high = Math.max(...values)
  const actualSpan = high - low
  const isFrequency = unit.toLowerCase() === 'hz'
  let minimumSpan = Math.max(Math.abs(low), Math.abs(high)) * 0.02 || 1
  // 用量纲和产品模板测点编码确定显示下限。跨度只影响坐标轴，真实波动较大时自动扩展。
  if (isFrequency) minimumSpan = point.category === 'ELECTRIC_METER' ? 0.5 : 0.2
  if (unit === '°C' && /(?:^|_)roomTemp$/i.test(point.pointCode ?? '')) minimumSpan = 2
  const padding = (Math.max(actualSpan * 1.25, minimumSpan) - actualSpan) / 2
  const lower = unit === 'W' && low > 0 ? Math.min(0, low - padding) : low - padding
  const upper = high + padding
  const suggestedStep = (upper - lower) / 4
  if (!Number.isFinite(suggestedStep) || suggestedStep <= 0) return null
  const magnitude = 10 ** Math.floor(Math.log10(suggestedStep))
  const factor = [1, 2, 2.5, 5, 10].find(candidate => candidate >= suggestedStep / magnitude - 1e-9) ?? 10
  const interval = Number((factor * magnitude).toPrecision(12))
  const [mantissa, exponent] = interval.toExponential(1).split('e')
  const digits = Math.min(20, Math.max(isFrequency ? 2 : 0, -Number(exponent) + (mantissa?.endsWith('.0') ? 0 : 1)))
  const number = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
  return {
    min: Number((Math.floor(lower / interval + 1e-9) * interval).toPrecision(12)),
    max: Number((Math.ceil(upper / interval - 1e-9) * interval).toPrecision(12)),
    interval,
    formatter: (value: number) => number.format(value),
  }
}
