import { describe, expect, it } from 'vitest'
import type { MonthlyComparisonResult } from '@/modules/energy-reporting/public'
import type { CalculationDetail } from '@/modules/energy-classification/public'
import type { ScreenPeriods } from './screen-redesign'
import { decimalValue, mapFinalBMonthly, previousMonth, staticElectricityFactor, validateMonthlyResult } from './final-b-monthly'

const month = { kind: 'month' as const, value: '2026-09' }
const year = { kind: 'year' as const, value: '2026' }
const periods: ScreenPeriods = { composition: month, flow: year, plan: year, mirror: year, usage: year, intensity: month }
export function monthlyFixture(): MonthlyComparisonResult {
  const current = { month: '2026-09-01', startInclusive: '2026-08-31T16:00:00Z', endExclusive: '2026-09-30T16:00:00Z', metrics: [
    { metricCode: 'ELECTRICITY_KWH' as const, unit: 'kWh', value: '41095.482000000000000' },
    { metricCode: 'TOTAL_EMISSION' as const, unit: 'tCO2e', value: '23.946337000000000000' },
    { metricCode: 'MONTH_AREA_INTENSITY' as const, unit: 'kgCO2e/m²', value: '0.670441000000000000' },
  ].map(metric => ({ ...metric, status: 'AVAILABLE', sourceBatchId: 'batch', evidenceHash: 'evidence', referenceVersionId: 'version' })) }
  const comparison = { currentValue: '23.946337', baseValue: '19.344036', absoluteChange: '4.602301', percentageChange: '23.791833', status: 'OK' }
  return { buildingId: 'B1', timezoneId: 'Asia/Shanghai', current, previousMonth: { ...current, month: '2026-08-01' }, sameMonthLastYear: { ...current, month: '2025-09-01' }, comparisons: [{ metricCode: 'TOTAL_EMISSION', unit: 'tCO2e', monthOnMonth: comparison, yearOnYear: { ...comparison, percentageChange: '38.518570' } }] }
}

describe('B screen backend result mapping', () => {
  it('maps the received percentages and monthly precision without annual aggregation', () => {
    const report = monthlyFixture(), data = mapFinalBMonthly(new Map([['2026-09', report]]), periods, '2026-09')
    expect(data.previousMonth).toEqual({ month: '2026-09', carbon: 23.946337, intensity: 0.670441 })
    expect(data.composition?.data.monthOverMonth).toBe(23.791833)
    expect(data.composition?.data.yearOverYear).toBe(38.51857)
    expect(data.usage?.data.electricity).toEqual([null, null, null, null, null, null, null, null, 41095.482, null, null, null])
    expect(data.usage?.data.temperature.every(value => value === null)).toBe(true)
    expect(data.intensity?.data.areaUnit).toBe('kgCO₂e/m²')
    expect(data.annual).toBeUndefined()
    expect(data.devices).toBeUndefined()
    expect(data.plan).toBeUndefined()
    expect(data.runtimeRanking).toBeUndefined()
  })
  it('keeps zero, unavailable units and a zero comparison base distinct', () => {
    const report = monthlyFixture()
    report.current.metrics[0]!.value = '0'
    report.current.metrics[1]!.unit = 'kgCO2e'
    report.current.metrics[2]!.status = 'MISSING'
    report.comparisons[0]!.monthOnMonth.status = 'BASE_ZERO'
    const data = mapFinalBMonthly(new Map([['2026-09', report]]), periods, '2026-09')
    expect(data.usage?.data.electricity[8]).toBe(0)
    expect(data.previousMonth?.carbon).toBeNull()
    expect(data.previousMonth?.intensity).toBeNull()
    expect(data.composition?.data.monthOverMonth).toBeNull()
    expect(data.mirror?.data.staticEmissions.every(value => value === null)).toBe(true)
  })
  it('rejects another building, month, timezone or time window', () => {
    const report = monthlyFixture()
    expect(() => validateMonthlyResult(report, 'B1', '2026-09')).not.toThrow()
    expect(() => validateMonthlyResult(report, 'B2', '2026-09')).toThrow()
    expect(() => validateMonthlyResult(report, 'B1', '2026-08')).toThrow()
    expect(() => validateMonthlyResult({ ...report, timezoneId: 'UTC' }, 'B1', '2026-09')).toThrow()
    expect(() => validateMonthlyResult({ ...report, current: { ...report.current, endExclusive: '2026-10-01T00:00:00Z' } }, 'B1', '2026-09')).toThrow()
  })
  it('only draws static carbon when frozen factor evidence is consistent with the batch', () => {
    const report = monthlyFixture()
    const detail = { calculationBatchId: 'batch', buildingId: 'B1', timezoneId: 'Asia/Shanghai', startInclusive: report.current.startInclusive, endExclusive: report.current.endExclusive, status: 'COMPLETED_COMPLETE', publicationStatus: 'DIRECT', resultNature: 'FORMAL', items: [{ energyItemCode: 'ELECTRICITY', scopeType: 'SCOPE_2', factorVersionId: 'factor', formulaVersionId: 'formula' }] } as CalculationDetail
    const frozen = { factorVersionId: 'factor', formulaVersionId: 'formula', gwpValue: '1.0', factor: { category: 'PURCHASED_ELECTRICITY_LOCATION', energyItemCode: 'ELECTRICITY', scopeType: 'SCOPE_2', gasCoverage: 'CO2_ONLY_ELECTRICITY', components: [{ type: 'DIRECT_EMISSION_FACTOR', unit: 'KG_CO2/KWH', value: '0.5827' }] } }
    expect(staticElectricityFactor(detail, frozen, report)).toBe(0.5827)
    expect(staticElectricityFactor(detail, { ...frozen, factorVersionId: 'other' }, report)).toBeNull()
    expect(staticElectricityFactor({ ...detail, buildingId: 'B2' }, frozen, report)).toBeNull()
    const data = mapFinalBMonthly(new Map([['2026-09', report]]), periods, '2026-09', new Map([['2026-09', 0.5827]]))
    expect(data.mirror?.data.staticEmissions[8]).toBe(23.946337)
    expect(data.mirror?.data.staticFactors[8]).toBe(0.5827)
    expect(data.mirror?.data.dynamicFactors.every(value => value === null)).toBe(true)
  })
  it('preserves month boundaries across the new year and rejects malformed decimal text', () => {
    expect(previousMonth(Date.UTC(2027, 0, 1))).toBe('2026-12')
    expect(previousMonth(Date.UTC(2026, 8, 30, 16))).toBe('2026-09')
    for (const value of ['', ' ', 'NaN', '1e1000', '12kWh']) expect(decimalValue(value)).toBeNull()
  })
})
