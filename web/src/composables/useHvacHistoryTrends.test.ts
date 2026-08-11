import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FROZEN_POINT_DEFINITIONS } from '@/domain/hvacDashboard'
import type {
  HvacIndicatorTrendResponse,
  HvacPointHistoryResponse,
  SnapshotPoint,
} from '@/types/hvac'
import { useHvacHistoryTrends } from './useHvacHistoryTrends'

const NOW = 1_800_000_000_000

function indicatorResponse(
  buildingId = 'BLD001',
  indicatorId = 'I1',
): HvacIndicatorTrendResponse {
  return {
    buildingId,
    from: NOW - 86_400_000,
    to: NOW,
    resolutionMinutes: 1,
    series: [{
      indicatorId,
      indicatorCode: 'WCR_COP',
      equipId: 'CHILLER',
      records: [{
        time: NOW - 60_000,
        average: 5.8,
        minimum: 5.8,
        maximum: 5.8,
        sampleCount: 1,
        dataQuality: 0,
      }],
    }],
  }
}

function pointResponse(
  pointId: string,
  pointCode: string,
): HvacPointHistoryResponse {
  return {
    buildingId: 'BLD001',
    from: NOW - 86_400_000,
    to: NOW,
    resolutionMinutes: 1,
    series: [{
      pointId,
      pointCode,
      pointName: '真实测点',
      unit: '℃',
      records: [],
    }],
  }
}

function snapshotPoints(count = 2, building = 'BLD001'): SnapshotPoint[] {
  return FROZEN_POINT_DEFINITIONS.slice(0, count).map((definition, index) => ({
    pointId: `${building}-P${index + 1}`,
    pointCode: definition.internalCode,
    pointName: definition.label,
    equipId: null,
    equipCode: null,
    unit: definition.unit,
    minute: null,
    average: null,
    minimum: null,
    maximum: null,
    sampleCount: 0,
    dataQuality: null,
    status: 'NO_DATA',
  }))
}

function dependencies() {
  return {
    getIndicatorTrends: vi.fn().mockResolvedValue(indicatorResponse()),
    getPointHistory: vi.fn().mockImplementation(
      (_buildingId: string, ids: string[]) => {
        const source = snapshotPoints(9).find((point) => point.pointId === ids[0])!
        return Promise.resolve(pointResponse(source.pointId, source.pointCode))
      },
    ),
    now: vi.fn(() => NOW),
  }
}

function controlled<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('useHvacHistoryTrends', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('loads the latest 24 hours of indicators when a building context arrives', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)

    await state.setBuildingContext({
      buildingId: 'BLD001',
      indicatorIds: ['I1'],
      points: snapshotPoints(),
    })

    expect(state.mode.value).toBe('indicators')
    expect(state.preset.value).toBe('24h')
    expect(api.getIndicatorTrends).toHaveBeenCalledWith(
      'BLD001',
      ['I1'],
      NOW - 86_400_000,
      NOW,
    )
    expect(state.groups.value[0].series[0].id).toBe('I1')
    expect(state.resolutionMinutes.value).toBe(1)
  })

  it('does not request point history until at least one point is selected', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    api.getPointHistory.mockClear()

    await state.setMode('points')

    expect(state.selectedPointIds.value).toEqual([])
    expect(api.getPointHistory).not.toHaveBeenCalled()
    expect(state.groups.value).toEqual([])
  })

  it('filters, caps, persists and queries selected points', async () => {
    const api = dependencies()
    const points = snapshotPoints(9)
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points,
    })
    await state.setMode('points')

    await state.setSelectedPointIds([
      ...points.map((point) => point.pointId),
      'INVALID',
    ])

    const selected = points.slice(0, 8).map((point) => point.pointId)
    expect(state.selectedPointIds.value).toEqual(selected)
    expect(JSON.parse(
      localStorage.getItem('hvac:history-point-ids:BLD001')!,
    )).toEqual(selected)
    expect(api.getPointHistory).toHaveBeenLastCalledWith(
      'BLD001', selected, NOW - 86_400_000, NOW,
    )
  })

  it('restores only valid stored ids for the current building and keeps eight', async () => {
    const points = snapshotPoints(9)
    localStorage.setItem(
      'hvac:history-point-ids:BLD001',
      JSON.stringify(['INVALID', ...points.map((point) => point.pointId)]),
    )
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setMode('points')

    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points,
    })

    expect(state.selectedPointIds.value).toEqual(
      points.slice(0, 8).map((point) => point.pointId),
    )
    expect(api.getPointHistory).toHaveBeenCalledTimes(1)
  })

  it('restores stored ids after an empty startup snapshot is replaced', async () => {
    const points = snapshotPoints(2)
    localStorage.setItem(
      'hvac:history-point-ids:BLD001',
      JSON.stringify([points[0].pointId]),
    )
    const api = dependencies()
    const state = useHvacHistoryTrends(api)

    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: [], points: [],
    })
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points,
    })

    expect(state.selectedPointIds.value).toEqual([points[0].pointId])
  })

  it('clears old building data immediately and ignores its late response', async () => {
    const first = controlled<HvacIndicatorTrendResponse>()
    const second = controlled<HvacIndicatorTrendResponse>()
    const api = dependencies()
    api.getIndicatorTrends
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise)
    const state = useHvacHistoryTrends(api)

    const firstLoad = state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    const secondLoad = state.setBuildingContext({
      buildingId: 'BLD002', indicatorIds: ['I2'], points: snapshotPoints(2, 'BLD002'),
    })
    expect(state.groups.value).toEqual([])

    second.resolve(indicatorResponse('BLD002', 'I2'))
    await secondLoad
    first.resolve(indicatorResponse('BLD001', 'I1'))
    await firstLoad

    expect(state.groups.value[0].series[0].id).toBe('I2')
    expect(state.responseRange.value).toEqual({
      from: NOW - 86_400_000,
      to: NOW,
    })
  })

  it('prevents an older same-building condition from overwriting a newer one', async () => {
    const oldRequest = controlled<HvacIndicatorTrendResponse>()
    const api = dependencies()
    api.getIndicatorTrends
      .mockImplementationOnce(() => oldRequest.promise)
      .mockResolvedValueOnce(indicatorResponse())
    const state = useHvacHistoryTrends(api)

    const initial = state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    await state.setPreset('7d')
    oldRequest.resolve(indicatorResponse())
    await initial

    expect(api.getIndicatorTrends).toHaveBeenLastCalledWith(
      'BLD001', ['I1'], NOW - 7 * 86_400_000, NOW,
    )
    expect(state.groups.value[0].series[0].id).toBe('I1')
  })

  it('keeps relative-window data as stale when its refreshed range advances and fails', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    api.now.mockReturnValue(NOW + 60_000)
    api.getIndicatorTrends.mockRejectedValueOnce({
      response: { status: 503, data: { msg: 'sensitive' } },
    })

    await state.refresh()

    expect(api.getIndicatorTrends).toHaveBeenLastCalledWith(
      'BLD001', ['I1'], NOW - 86_400_000 + 60_000, NOW + 60_000,
    )
    expect(state.groups.value).not.toEqual([])
    expect(state.stale.value).toBe(true)
    expect(state.error.value).toEqual({
      status: 503,
      message: '历史趋势暂不可用，请稍后重试',
    })
  })

  it('clears mismatched data when a changed condition fails', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    api.getIndicatorTrends.mockRejectedValueOnce(new Error('offline'))

    await state.setPreset('7d')

    expect(state.groups.value).toEqual([])
    expect(state.stale.value).toBe(false)
    expect(state.error.value?.message).toBe('offline')
  })

  it('maps invalid custom ranges locally without sending a request', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    api.getIndicatorTrends.mockClear()

    await state.setCustomRange(NOW, NOW)

    expect(api.getIndicatorTrends).not.toHaveBeenCalled()
    expect(state.groups.value).toEqual([])
    expect(state.error.value?.message).toBe('开始时间必须早于结束时间')
  })

  it('opens an empty custom range without reporting an input error', async () => {
    const api = dependencies()
    const state = useHvacHistoryTrends(api)
    await state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    api.getIndicatorTrends.mockClear()

    await state.setPreset('custom')

    expect(state.preset.value).toBe('custom')
    expect(state.groups.value).toEqual([])
    expect(state.error.value).toBeNull()
    expect(api.getIndicatorTrends).not.toHaveBeenCalled()
  })

  it('dispose invalidates a request that resolves after unmount', async () => {
    const pending = controlled<HvacIndicatorTrendResponse>()
    const api = dependencies()
    api.getIndicatorTrends.mockImplementation(() => pending.promise)
    const state = useHvacHistoryTrends(api)

    const request = state.setBuildingContext({
      buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
    })
    state.dispose()
    pending.resolve(indicatorResponse())
    await request

    expect(state.groups.value).toEqual([])
    expect(state.loading.value).toBe(false)
  })
})
