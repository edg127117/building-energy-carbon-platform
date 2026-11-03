import type { CarbonPlanResponse } from '@/modules/energy-classification/public'

/** 与B卡片现有数据形状一致；只作十进制到图表数值转换，不在浏览器计算业务指标。 */
export interface ScreenCarbonPlanData {
  annualBaseline: number | null; reductionPercent: number; remainingQuota: number | null
  baseline: Array<number | null>; quota: Array<number | null>; actual: Array<number | null>; unit: string
}
const number = (value: string | null): number | null => {
  if (value === null || !value.trim()) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
export function adaptCarbonPlan(response: CarbonPlanResponse): ScreenCarbonPlanData | null {
  if (!response.plan || response.annualBaseline === null || response.reductionPercent === null) return null
  const annual = number(response.annualBaseline), reduction = number(response.reductionPercent)
  if (annual === null || reduction === null) return null
  return {
    annualBaseline: annual, reductionPercent: reduction,
    remainingQuota: number(response.remainingQuota), unit: response.unit,
    baseline: Array.from({ length: 12 }, (_, i) => number(response.months.find(m => m.month === i + 1)?.baseline ?? null)),
    quota: Array.from({ length: 12 }, (_, i) => number(response.months.find(m => m.month === i + 1)?.quota ?? null)),
    actual: Array.from({ length: 12 }, (_, i) => {
      const month = response.months.find(m => m.month === i + 1)
      return month?.actualStatus === 'AVAILABLE' ? number(month.actual) : null
    }),
  }
}
