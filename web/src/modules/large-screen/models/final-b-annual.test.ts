import { describe, expect, it } from 'vitest'
import { annualScreenQuery, screenAnnualComposition, screenAnnualOverview } from './final-b-annual'
import { annualFixture } from './final-b-annual.test-fixture'

const now = Date.parse('2026-10-31T10:00:00+08:00')
describe('B annual display contract', () => {
  it('uses closed-month YTD and explicit full twelve-month history, never formal-year fallback', () => {
    expect(annualScreenQuery('B', '2026', now)).toMatchObject({ year: 2026, throughMonth: 9, mode: 'MONTHLY_YTD' })
    expect(annualScreenQuery('B', '2025', now)).toMatchObject({ year: 2025, throughMonth: 12, mode: 'MONTHLY_YTD' })
    expect(annualScreenQuery('B', '2027', now)).toBeNull()
    expect(annualScreenQuery('', '2026', now)).toBeNull()
    expect(annualScreenQuery('B', '2026-01', now)).toBeNull()
    expect(annualScreenQuery('B', '2026', Date.parse('2026-01-31T00:00:00+08:00'))).toBeNull()
  })
  it('copies backend cumulative intensity and percentage without summing or deriving them', () => {
    const view = annualFixture(annualScreenQuery('B', '2026', now)!)
    expect(screenAnnualOverview(view)).toEqual({ year: '2026', throughMonth: 9, carbon: 129.456789, intensity: 6.472839 })
    expect(screenAnnualComposition(view, { kind: 'year', value: '2026' })?.data).toEqual({
      total: 129.456789, throughMonth: 9, yearOverYear: -7.25, monthOverMonth: null, unit: 'tCO₂e',
    })
  })
  it('keeps real zero, missing area, missing month, base zero and incompatible units distinct', () => {
    const view = annualFixture(annualScreenQuery('B', '2026', now)!)
    view.current.metrics[0].value = '0'
    view.current.metrics[1].status = 'AREA_GAP'
    view.comparisons[0].yearOnYear.status = 'BASE_ZERO'
    expect(screenAnnualOverview(view)).toMatchObject({ carbon: 0, intensity: null })
    expect(screenAnnualComposition(view, { kind: 'year', value: '2026' })?.data.yearOverYear).toBeNull()
    view.current.metrics[0].status = 'MISSING_MONTHS'
    expect(screenAnnualOverview(view)?.carbon).toBeNull()
    view.current.metrics[0].status = 'AVAILABLE'; view.current.metrics[0].unit = 'kgCO2e'
    expect(screenAnnualOverview(view)?.carbon).toBeNull()
  })
})
