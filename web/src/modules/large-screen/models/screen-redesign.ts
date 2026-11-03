import type { ScreenPeriod } from './screen-period'
import type { ScreenMirrorData, ScreenPlanData, ScreenUsageData } from './screen-analytical'
import type { ScreenAlarmSummaryData, ScreenDeviceSummaryData, ScreenIntensityData, ScreenRuntimeRankingEntry, ScreenIntensityBenchmarks } from './screen-summaries'

export interface ScreenPeriodEntry<T> { period: ScreenPeriod; data: T }
export interface ScreenPeriods {
  composition: ScreenPeriod
  flow: ScreenPeriod
  plan: ScreenPeriod
  mirror: ScreenPeriod
  usage: ScreenPeriod
  intensity: ScreenPeriod
}
export interface ScreenCompositionData { total: number | null; yearOverYear: number | null; monthOverMonth: number | null; unit: string; throughMonth?: number }
export interface ScreenBusinessFlowGraph {
  unit: string
  total: number | null
  // 节点提示使用显式节点值；缺少时不从后端连线重算业务读数。columns 与 order 均来自已选展示配置。
  columns?: string[]
  nodes: Array<{ id: string; name: string; depth: number; order?: number; value?: number | null }>
  links: Array<{ source: string; target: string; value: number }>
}

/** 大屏展示输入；独立原型和业务接口显式分开，旧能源/系统流图不能冒充用途/楼层图。 */
export interface ScreenRedesignData {
  annual?: { year: string; carbon: number | null; intensity: number | null; throughMonth?: number }
  previousMonth?: { month: string; carbon: number | null; intensity: number | null }
  composition?: ScreenPeriodEntry<ScreenCompositionData>
  carbonFlow?: ScreenPeriodEntry<ScreenBusinessFlowGraph>
  energyFlow?: ScreenPeriodEntry<ScreenBusinessFlowGraph>
  plan?: ScreenPeriodEntry<ScreenPlanData>
  mirror?: ScreenPeriodEntry<ScreenMirrorData>
  usage?: ScreenPeriodEntry<ScreenUsageData>
  intensity?: ScreenPeriodEntry<ScreenIntensityData>
  devices?: ScreenDeviceSummaryData
  deviceSummaryUpdatedAt?: number
  alarms?: ScreenAlarmSummaryData
  runtimeRanking?: readonly ScreenRuntimeRankingEntry[]
  intensityBenchmarks?: ScreenIntensityBenchmarks
}

export function periodEntry<T>(entry: ScreenPeriodEntry<T> | undefined, period: ScreenPeriod): T | undefined {
  return entry?.period.kind === period.kind && entry.period.value === period.value ? entry.data : undefined
}

// 固定原型计划只服务于视觉评审，不进入正式核算结果。
export const PLAN_PROTOTYPE_YEAR = '2026'
export const PLAN_PROTOTYPE: ScreenPlanData = {
  annualBaseline: 160,
  reductionPercent: 15,
  remainingQuota: 32,
  baseline: [12, 10, 12, 13, 14, 15, 17, 17, 15, 14, 11, 10],
  quota: [10.2, 8.5, 10.2, 11.05, 11.9, 12.75, 14.45, 14.45, 12.75, 11.9, 9.35, 8.5],
  actual: [7, 6, 8, 8, 10, 12, 14, 14, 9, 8, 4, 4],
  unit: 'tCO₂e',
}
