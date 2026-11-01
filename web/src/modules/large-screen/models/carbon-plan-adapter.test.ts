import { expect, it } from 'vitest'
import type { CarbonPlanResponse } from '@/modules/energy-classification/public'
import { adaptCarbonPlan } from './carbon-plan-adapter'

it('preserves backend fixed annual value, negative remaining, zero actual and all twelve months', () => {
  const response = { plan: {}, annualBaseline: '160', reductionPercent: '15', remainingQuota: '-4', unit: 'tCO2e', months: [
    { month: 1, baseline: '10', quota: '8.5', actual: '0', actualStatus: 'AVAILABLE' },
    { month: 2, baseline: '10', quota: '8.5', actual: null, actualStatus: 'NO_FORMAL_RESULT' },
    { month: 3, baseline: '10', quota: '8.5', actual: '140', actualStatus: 'AVAILABLE' },
  ] } as CarbonPlanResponse
  const result = adaptCarbonPlan(response)!
  expect(result.annualBaseline).toBe(160)
  expect(result.remainingQuota).toBe(-4)
  expect(result.actual).toHaveLength(12)
  expect(result.actual.slice(0, 3)).toEqual([0, null, 140])
  expect(result.quota.slice(0, 3)).toEqual([8.5, 8.5, 8.5])
  response.remainingQuota = null
  expect(adaptCarbonPlan(response)?.remainingQuota).toBeNull()
  response.plan = null
  expect(adaptCarbonPlan(response)).toBeNull()
})
