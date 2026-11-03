import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { annualDisplayValue, readAnnualOverview, validateAnnualOverview, type AnnualOverview, type AnnualQuery } from './annual-overview'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
const query: AnnualQuery = { buildingId: 'other/building', year: 2024, mode: 'MONTHLY_YTD', throughMonth: 9, timezoneId: 'Asia/Shanghai' }
const result = (): AnnualOverview => ({ buildingId: query.buildingId, year: 2024, mode: 'MONTHLY_YTD', throughMonth: 9, timezoneId: query.timezoneId,
  algorithmVersion: 'v1', evidenceHash: 'hash', current: { start: '2024-01-01', endExclusive: '2024-10-01', startInclusive: '', endInstantExclusive: '', averageArea: '100', areaStatus: 'AVAILABLE', areaParts: [],
    metrics: [{ code: 'YTD_AREA_INTENSITY', unit: 'kgCO2e/m²', value: '1.25', status: 'AVAILABLE', missingMonths: [], sources: [] }] },
  samePeriodLastYear: {} as AnnualOverview['current'], comparisons: [] })
describe('年度概览契约适配', () => {
  it('编码建筑身份并原样传递模式截止月时区和取消信号', async () => {
    const signal = new AbortController().signal
    await readAnnualOverview(query, signal)
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/energy-reporting/buildings/other%2Fbuilding/annual-overview', params: { year: 2024, mode: 'MONTHLY_YTD', throughMonth: 9, timezoneId: 'Asia/Shanghai' }, signal })
  })
  it('拒绝跨建筑跨模式或错误期间，完整保留后端值', () => {
    const value = result()
    expect(() => validateAnnualOverview(value, query)).not.toThrow()
    expect(() => validateAnnualOverview({ ...value, mode: 'FORMAL_YEAR' }, query)).toThrow()
    expect(() => validateAnnualOverview({ ...value, buildingId: 'wrong' }, query)).toThrow()
    expect(annualDisplayValue(value, 'YTD_AREA_INTENSITY', 'kgCO2e/m²')).toBe(1.25)
    expect(annualDisplayValue(value, 'YTD_AREA_INTENSITY', 'tCO2e')).toBeNull()
    value.current.metrics[0].status = 'AREA_GAP'
    expect(annualDisplayValue(value, 'YTD_AREA_INTENSITY', 'kgCO2e/m²')).toBeNull()
  })
})
