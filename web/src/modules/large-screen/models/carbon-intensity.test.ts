import { describe, it, expect } from 'vitest'
import { intensityQuery, mapCarbonIntensity, validateCarbonIntensity } from './carbon-intensity'
import { intensityView } from './carbon-intensity.test-fixture'

describe('carbon intensity presentation', () => {
  it('passes through backend values including zero and over100, retaining distinct actual and reference units', () => {
    const view = intensityView(), period = { kind: 'year' as const, value: '2025' }
    const result = mapCarbonIntensity(view, period)
    expect(result.intensity.data.areaValue).toBe(85.065)
    expect(result.intensity.data.areaPercentage).toBe(150)
    expect(result.intensity.data.areaUnit).toBe('kgCO₂e/m²')
    expect(result.intensityBenchmarks.unit).toBe('kgCO2/(m²·年)')
    expect(mapCarbonIntensity({ ...view, percentage: '0', actual: { ...view.actual, value: '0' } }, period).intensity.data).toMatchObject({ areaValue: 0, areaPercentage: 0 })
    expect(result.intensityBenchmarks.versionId).toBe('standard-v1')
  })
  it('never calculates a percentage from actual and constraint or rates monthly/ytd data', () => {
    for (const mode of ['MONTH', 'MONTHLY_YTD'] as const) expect(mapCarbonIntensity(intensityView({ mode }), { kind: 'year', value: '2025' }).intensity.data.areaPercentage).toBeNull()
    expect(mapCarbonIntensity(intensityView({ percentage: null }), { kind: 'year', value: '2025' }).intensity.data.areaPercentage).toBeNull()
    expect(mapCarbonIntensity(intensityView({ comparisonStatus: 'NOT_COMPARABLE', percentageStatus: 'UNAVAILABLE', reasons: ['APPLICABILITY_TIME_UNCLEAR'] }), { kind: 'year', value: '2025' }).intensity.data.areaPercentage).toBeNull()
    const bad = intensityView(); bad.actual.unit = 'kg/person'
    expect(mapCarbonIntensity(bad, { kind: 'year', value: '2025' }).intensity.data.areaValue).toBeNull()
    expect(mapCarbonIntensity(intensityView({ reference: null }), { kind: 'year', value: '2025' }).intensityBenchmarks.constraint).toBeNull()
  })
  it('shows automatic annual and YTD backend ratios while retaining unknown-time notice', () => {
    for (const mode of ['FORMAL_YEAR', 'MONTHLY_YTD'] as const) {
      const view = intensityView({ mode, matchStatus: 'APPLICABILITY_TIME_UNCLEAR', comparisonStatus: 'NOT_COMPARABLE', percentageBasis: mode === 'FORMAL_YEAR' ? 'ANNUAL_REFERENCE' : 'YTD_SHARE_OF_ANNUAL_REFERENCE', percentage: '50', reasons: ['APPLICABILITY_TIME_UNCLEAR'] })
      const mapped = mapCarbonIntensity(view, { kind: 'year', value: '2026' })
      expect(mapped.intensity.data.areaPercentage).toBe(50)
      expect(mapped.intensityBenchmarks.reasons).toContain('APPLICABILITY_TIME_UNCLEAR')
      expect(mapCarbonIntensity({ ...view, percentage: null, percentageStatus: 'UNAVAILABLE' }, { kind: 'year', value: '2026' }).intensity.data.areaPercentage).toBeNull()
    }
  })
  it('selects formal past year, current completed-month ytd, and monthly contracts without annualization', () => {
    const now = Date.parse('2026-10-30T10:00:00+08:00')
    expect(intensityQuery('B', { kind: 'year', value: '2026' }, now)).toMatchObject({ mode: 'MONTHLY_YTD', year: 2026, month: 9 })
    expect(intensityQuery('B', { kind: 'year', value: '2025' }, now)).toMatchObject({ mode: 'FORMAL_YEAR', year: 2025 })
    expect(intensityQuery('B', { kind: 'month', value: '2026-09' }, now)).toMatchObject({ mode: 'MONTH', month: 9 })
    expect(intensityQuery('B', { kind: 'year', value: '2026' }, Date.parse('2026-01-01T00:00:00+08:00'))).toBeNull()
    expect(intensityQuery('', { kind: 'year', value: '2025' }, now)).toBeNull()
  })
  it('rejects wrong building, timezone, mode, or window responses', () => {
    const q = { buildingId: 'B', mode: 'FORMAL_YEAR' as const, year: 2025, timezoneId: 'Asia/Shanghai' }
    expect(() => validateCarbonIntensity(intensityView(), q)).not.toThrow()
    for (const override of [{ buildingId: 'other' }, { timezoneId: 'UTC' }, { mode: 'MONTH' as const }, { endExclusive: '2025-12-31' }]) expect(() => validateCarbonIntensity(intensityView(override), q)).toThrow('SCOPE_MISMATCH')
  })
})
