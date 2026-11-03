import { describe, expect, it } from 'vitest'
import type { BuildingDeviceSummary, BuildingRuntimeRanking } from '../api/building-monitoring'
import { mapBuildingDeviceSummary, mapBuildingRuntimeRanking, validateBuildingDeviceSummary, validateBuildingRuntimeRanking } from './building-monitoring'
import summaryMessages from '../locales/summaries'

const categories = (counts: Record<string, number>, unknown = 0, reasons: Record<string, number> = {}) => ({
  counts, observed: Object.values(counts).reduce((sum, value) => sum + value, 0), unknown, unsupported: 0, reasons,
})
function summary(overrides: Partial<BuildingDeviceSummary> = {}): BuildingDeviceSummary {
  return { buildingId: 'B', scope: 'ACTIVE_BOUND_DAIKIN_INDOOR', asOf: 1000, expiresAt: 2000, total: 2, on: 1, off: 1, unknown: 0,
    unknownReasons: {}, modes: categories({ fan: 1, cooling: 0, heating: 0, dehumidify: 0 }),
    speeds: categories({ low: 1, middle: 0, high: 0, automatic: 0, middleLow: 0, middleHigh: 0 }), ...overrides }
}
function ranking(overrides: Partial<BuildingRuntimeRanking> = {}): BuildingRuntimeRanking {
  const start = Date.UTC(2026, 9, 1) - 8 * 60 * 60 * 1000
  return { buildingId: 'B', scope: 'ACTIVE_BOUND_DAIKIN_INDOOR', source: 'PLATFORM_OBSERVED', month: '2026-10', statisticsZone: 'Asia/Shanghai',
    asOf: start + 1000, periodStart: start, periodEnd: Date.UTC(2026, 10, 1) - 8 * 60 * 60 * 1000,
    elapsedMillis: 1000, totalDevices: 1, rankedDevices: 1, excludedReasons: {},
    items: [{ rank: 1, equipmentId: 'E1', identityId: 'I1', mappingVersion: 2, equipmentCode: 'AC-01', equipmentName: '内机',
      spaceName: '会议室', floorName: null, onMillis: 1000, coveredMillis: 1000, hours: 0.0003,
      coveredHours: 0.0003, coveragePercentage: 100, percentage: 100, coverageStatus: 'PARTIAL' }], ...overrides }
}

describe('building monitoring contract mapping', () => {
  it('keeps the visible 3 mode and 4 speed rows and leaves absent protocol keys unavailable', () => {
    const mapped = mapBuildingDeviceSummary(summary({
      modes: categories({}, 1, { MISSING: 1 }), speeds: categories({}, 1, { MISSING: 1 }),
    }))
    expect(mapped.modes.map(entry => [entry.key, entry.count])).toEqual([['fan', null], ['cool', null], ['heat', null]])
    expect(mapped.speeds.map(entry => [entry.key, entry.count])).toEqual([['low', null], ['medium', null], ['high', null], ['auto', null]])
    expect(mapped.modes[0].label).toBe(summaryMessages.deviceSummaryFan)
    expect(mapped.total).toBe(2)
  })

  it('rejects wrong summary scope/building and inconsistent status totals', () => {
    expect(() => validateBuildingDeviceSummary(summary(), 'OTHER')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ scope: 'OTHER' as never }), 'B')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ on: 2 }), 'B')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ modes: categories({ fan: 1, cooling: 1 }) }), 'B')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ modes: categories({}, 1) }), 'B')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ unknownReasons: { STALE: 1 } }), 'B')).toThrow()
    expect(() => validateBuildingDeviceSummary(summary({ modes: categories({}, 1, { STALE: 1 }) }), 'B')).not.toThrow()
  })

  it('validates ranking scope, source, month window and duplicate device identities', () => {
    expect(() => validateBuildingRuntimeRanking(ranking(), 'B', '2026-10')).not.toThrow()
    const truncated = ranking({ totalDevices: 40, rankedDevices: 30, items: Array.from({ length: 5 }, (_, index) => ({
      ...ranking().items[0], rank: index + 1, equipmentId: `E${index}`, identityId: `I${index}`,
    })) })
    expect(() => validateBuildingRuntimeRanking(truncated, 'B', '2026-10')).not.toThrow()
    expect(() => validateBuildingRuntimeRanking(ranking({ items: [{ ...ranking().items[0], coveredMillis: 1001 }] }), 'B', '2026-10')).toThrow()
    expect(() => validateBuildingRuntimeRanking(ranking({ items: [{ ...ranking().items[0], coveragePercentage: 101 }] }), 'B', '2026-10')).toThrow()
    expect(() => validateBuildingRuntimeRanking(ranking({ items: [{ ...ranking().items[0], coveredHours: -1 }] }), 'B', '2026-10')).toThrow()
    expect(() => validateBuildingRuntimeRanking(ranking({ source: 'SYNTHETIC' as never }), 'B', '2026-10')).toThrow()
    expect(() => validateBuildingRuntimeRanking(ranking({ periodStart: 0 }), 'B', '2026-10')).toThrow()
    const duplicate = ranking(); duplicate.items.push({ ...duplicate.items[0], rank: 2 })
    duplicate.rankedDevices = 2; duplicate.totalDevices = 2
    expect(() => validateBuildingRuntimeRanking(duplicate, 'B', '2026-10')).toThrow()
  })

  it('displays formal space and equipment identity without guessing a missing floor', () => {
    const [item] = mapBuildingRuntimeRanking(ranking())
    expect(item).toMatchObject({ room: '会议室 · 内机', floor: '—', coverageStatus: 'PARTIAL', coveredHours: 0.0003, coveragePercentage: 100 })
  })
})
