import { describe, expect, it } from 'vitest'
import type { CalculationDetail } from '@/modules/energy-classification/public'
import { carbonComparisonSeries } from './screen-chart-data'
function detail(total: string, breakdown: Record<string, string> = {}): CalculationDetail {
  const metric = (metricCode: string, dimensionCode: string, finalValue: string) => ({ metricCode, dimensionCode, finalValue, rawValue: finalValue, unitCode: 'tCO2e', denominatorVersionId: null, unavailableReason: null, evidenceHash: 'hash' })
  return { summaries: [metric('TOTAL_EMISSION', 'TOTAL', total), ...Object.entries(breakdown).map(([code, value]) => metric('ENERGY_ITEM_EMISSION', code, value))] } as CalculationDetail
}
describe('screen authority chart values', () => {
  it('keeps three periods, backend energy summaries, missing periods and real zero', () => {
    expect(carbonComparisonSeries([detail('2', { ELECTRICITY: '2' }), detail('0', { ELECTRICITY: '0' }), null])).toEqual([{ name: 'ELECTRICITY', values: [2, 0, null] }])
  })
  it('falls back to server totals when energy dimensions differ, without summing detail', () => {
    const series = carbonComparisonSeries([detail('999', { GAS: '1' }), detail('888', { ELECTRICITY: '2' }), null])
    expect(series[0].values).toEqual([999, 888, null])
    expect(series).toHaveLength(1)
  })
})
