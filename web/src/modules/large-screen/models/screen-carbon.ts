import type {
  CalculationDetail,
  CarbonClassificationReport,
  MonthlyCalculationResult,
} from '@/modules/energy-classification/public'

export const SCREEN_CARBON_TIME_ZONE = 'Asia/Shanghai'

const FORMAL_COMPLETE_STATUS = 'COMPLETED_COMPLETE'
const FORMAL_PUBLICATION_STATUSES = new Set(['DIRECT', 'PUBLISHED'])
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1_000

export type ScreenCarbonState = 'ready' | 'empty' | 'limited' | 'ambiguous' | 'error'
export type ScreenCarbonReason =
  | 'EMPTY'
  | 'MISSING'
  | 'NOT_IN_LIST'
  | 'MULTIPLE_MATCHES'
  | 'MONTH_RESULT_SCOPE_MISMATCH'
  | 'MONTH_RESULT_FAILED'
  | 'DETAIL_SCOPE_MISMATCH'
  | 'REPORT_SCOPE_MISMATCH'
  | 'FLOW_UNAVAILABLE'
  | 'REQUEST_FAILED'

export interface ScreenCarbonMonthWindow {
  month: string
  startInclusive: string
  endExclusive: string
}

export interface ScreenMonthlyCarbonSelection {
  detail: CalculationDetail | null
  state: Exclude<ScreenCarbonState, 'limited'>
  reason: ScreenCarbonReason | null
}

function parseMonth(month: string): { year: number; value: number } | null {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month)
  if (!match) return null
  const year = Number(match[1])
  const value = Number(match[2])
  return year > 0 ? { year, value } : null
}

function formatMonth(year: number, value: number): string {
  return `${String(year).padStart(4, '0')}-${String(value).padStart(2, '0')}`
}

function exactInstant(value: string): number | null {
  if (!/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 北京自然月统一转换为半开 UTC 时间窗，避免浏览器本地时区影响批次匹配。 */
export function beijingMonthWindow(month: string): ScreenCarbonMonthWindow | null {
  const parsed = parseMonth(month)
  if (!parsed) return null
  const start = Date.UTC(parsed.year, parsed.value - 1, 1) - BEIJING_OFFSET_MS
  const end = Date.UTC(parsed.year, parsed.value, 1) - BEIJING_OFFSET_MS
  return {
    month,
    startInclusive: new Date(start).toISOString(),
    endExclusive: new Date(end).toISOString(),
  }
}

export function previousCompleteBeijingMonth(): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: SCREEN_CARBON_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date())
  let year = Number(parts.find(part => part.type === 'year')?.value)
  let value = Number(parts.find(part => part.type === 'month')?.value)
  if (value === 1) {
    year -= 1
    value = 12
  } else value -= 1
  return formatMonth(year, value)
}

export function comparisonMonths(month: string): string[] | null {
  const parsed = parseMonth(month)
  if (!parsed || parsed.year <= 1) return null
  const previousYear = formatMonth(parsed.year - 1, parsed.value)
  const previousMonth = parsed.value === 1
    ? formatMonth(parsed.year - 1, 12)
    : formatMonth(parsed.year, parsed.value - 1)
  return [previousYear, month, previousMonth]
}

export function hasExactBeijingMonth(
  startInclusive: string,
  endExclusive: string,
  target: ScreenCarbonMonthWindow,
): boolean {
  return exactInstant(startInclusive) === exactInstant(target.startInclusive)
    && exactInstant(endExclusive) === exactInstant(target.endExclusive)
}

export function isCurrentFormalCarbonDetail(
  detail: CalculationDetail,
  batchId: string,
  buildingId: string,
  target: ScreenCarbonMonthWindow,
): boolean {
  return detail.calculationBatchId === batchId
    && detail.buildingId === buildingId
    && detail.periodType === 'MONTH'
    && detail.timezoneId === SCREEN_CARBON_TIME_ZONE
    && detail.resultNature === 'FORMAL'
    && detail.status === FORMAL_COMPLETE_STATUS
    && FORMAL_PUBLICATION_STATUSES.has(detail.publicationStatus)
    && hasExactBeijingMonth(detail.startInclusive, detail.endExclusive, target)
}

/**
 * 月结果端点已经以北京自然月裁决唯一性；大屏只把 READY 中同身份的冻结详情用于展示，
 * 绝不从历史列表回退猜测批次。
 */
export function selectScreenMonthlyCarbonResult(
  result: MonthlyCalculationResult | null | undefined,
  buildingId: string,
  target: ScreenCarbonMonthWindow,
): ScreenMonthlyCarbonSelection {
  if (!result || result.month !== target.month) {
    return { detail: null, state: 'error', reason: 'MONTH_RESULT_SCOPE_MISMATCH' }
  }
  if (result.state === 'MISSING') return { detail: null, state: 'empty', reason: 'MISSING' }
  if (result.state === 'AMBIGUOUS') return { detail: null, state: 'ambiguous', reason: 'MULTIPLE_MATCHES' }
  if (result.state === 'FAILED') return { detail: null, state: 'error', reason: 'MONTH_RESULT_FAILED' }
  if (result.state !== 'READY' || !result.batch
    || !isCurrentFormalCarbonDetail(result.batch, result.batch.calculationBatchId, buildingId, target)) {
    return { detail: null, state: 'error', reason: 'DETAIL_SCOPE_MISMATCH' }
  }
  return { detail: result.batch, state: 'ready', reason: null }
}

export function isCurrentCarbonFlowReport(
  report: CarbonClassificationReport,
  batchId: string,
  buildingId: string,
): boolean {
  return report.buildingId === buildingId
    && report.batchId === batchId
    && report.unit === 'KG_CO2E'
    && report.status === FORMAL_COMPLETE_STATUS
}

export function hasAvailableCarbonFlow(report: CarbonClassificationReport): boolean {
  return Array.isArray(report.flows) && report.flows.some(flow => flow.available === true
    && Array.isArray(flow.links) && flow.links.length > 0)
}

/** 只读取后端写入的唯一同口径汇总，拒绝不可用、重复或单位不一致的指标。 */
export function readCarbonSummary(
  detail: CalculationDetail | null | undefined,
  metricCode: string,
  dimensionCode: string,
  unitCode: string,
): number | null {
  if (!detail) return null
  const matches = detail.summaries.filter(summary => summary.metricCode === metricCode
    && summary.dimensionCode === dimensionCode && summary.unitCode === unitCode)
  if (matches.length !== 1) return null
  const summary = matches[0]
  if (summary.unavailableReason !== null || typeof summary.finalValue !== 'string'
    || summary.finalValue.trim().length === 0) return null
  const value = Number(summary.finalValue)
  return Number.isFinite(value) ? value : null
}
