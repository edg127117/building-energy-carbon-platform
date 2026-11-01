import type { AnnualOverview, AnnualQuery } from '@/modules/energy-reporting/public'

export function annualFixture(query: AnnualQuery): AnnualOverview {
  const cutoff = query.mode === 'FORMAL_YEAR' ? 12 : query.throughMonth!
  const end = `${query.year + (cutoff === 12 ? 1 : 0)}-${String(cutoff === 12 ? 1 : cutoff + 1).padStart(2, '0')}-01`
  const current = {
    start: `${query.year}-01-01`, endExclusive: end,
    startInclusive: new Date(Date.UTC(query.year, 0, 1) - 28800000).toISOString(),
    endInstantExclusive: new Date(Date.UTC(query.year, cutoff, 1) - 28800000).toISOString(),
    metrics: [
      { code: 'TOTAL_EMISSION', unit: 'tCO2e', value: '129.456789', status: 'AVAILABLE', missingMonths: [], sources: [] },
      { code: query.mode === 'MONTHLY_YTD' ? 'YTD_AREA_INTENSITY' : 'AREA_INTENSITY', unit: 'kgCO2e/m²', value: '6.472839', status: 'AVAILABLE', missingMonths: [], sources: [] },
    ], averageArea: '20000', areaStatus: 'AVAILABLE', areaParts: [],
  }
  return { buildingId: query.buildingId, year: query.year, mode: query.mode, throughMonth: cutoff, timezoneId: query.timezoneId,
    algorithmVersion: 'ANNUAL_OVERVIEW_AREA_DAY_V1', evidenceHash: 'isolated-annual-fixture', current,
    samePeriodLastYear: { ...current, start: `${query.year - 1}-01-01` },
    comparisons: [{ code: 'TOTAL_EMISSION', yearOnYear: { currentValue: '129.456789', baseValue: '139.576053',
      absoluteChange: '-10.119264', percentageChange: '-7.25', status: 'OK' } }],
  }
}
