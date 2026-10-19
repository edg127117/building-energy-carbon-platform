import { describe, expect, it } from 'vitest'
import { cloneConfiguration, mapCsvReadings, readingCsvFields, validateManualReadings, type ClassificationConfiguration, type ReadingCsvMapping } from './classification'

const mapping = Object.fromEntries(readingCsvFields.map(field => [field, field])) as ReadingCsvMapping

describe('通用计量分类模型', () => {
  it('待专家确认的空倍率保留为空且不在浏览器默认换算', () => {
    const pending = mapCsvReadings([reading('PENDING_EXPERT', '')], mapping)
    const confirmed = mapCsvReadings([reading('CONFIRMED', '')], mapping)
    const decimal = mapCsvReadings([reading('CONFIRMED', '1.2500')], mapping)

    expect(pending[0]?.multiplier).toBeNull()
    expect(validateManualReadings(pending)).toBeNull()
    expect(validateManualReadings(confirmed)).toBe('MULTIPLIER')
    expect(decimal[0]?.multiplier).toBe('1.2500')
  })

  it('旧配置缺少独立通道确认时保守默认为未确认', () => {
    const legacy = {
      relationVersionId: 'relation-1', schemes: [], nodes: [], bindings: [],
      boundaryRules: [{ boundaryId: 'boundary-1', energyItemCode: 'ELECTRICITY', unit: 'kWh', mode: 'INDEPENDENT_METER_SUM', status: 'PENDING_EXPERT', evidence: null }],
    } as unknown as ClassificationConfiguration

    expect(cloneConfiguration(legacy).boundaryRules[0]?.independentChannelsConfirmed).toBe(false)
  })
})

function reading(status: 'CONFIRMED' | 'PENDING_EXPERT', multiplier: string) {
  return {
    pointId: 'point-1', startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z',
    energyItemCode: 'ELECTRICITY', unit: 'kWh', semantics: 'PERIOD_TOTAL', startReading: '', endReading: '', quantity: '12.50',
    multiplier, alreadyScaled: 'false', status, sourceReference: 'manual', timePrecision: 'EXACT', supersedesFactId: '',
  }
}
