import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { useHvacDashboard } from './useHvacDashboard'

const building = {
  buildingId: 'BLD001',
  buildingName: '所在大楼',
  buildingCode: 'BLD001',
  buildingType: '办公',
  climateZone: '夏热冬冷',
}

const snapshot = {
  buildingId: 'BLD001',
  generatedAt: 100,
  points: [],
}

const indicators = {
  buildingId: 'BLD001',
  generatedAt: 101,
  indicators: [],
}

function dependencies() {
  return {
    listAccessibleBuildings: vi.fn().mockResolvedValue([building]),
    getHvacSnapshot: vi.fn().mockResolvedValue(snapshot),
    getLatestHvacIndicators: vi.fn().mockResolvedValue(indicators),
  }
}

describe('useHvacDashboard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('selects the first accessible building and loads both sections', async () => {
    const api = dependencies()
    const dashboard = useHvacDashboard(api)

    await dashboard.initialize()

    expect(dashboard.selectedBuildingId.value).toBe('BLD001')
    expect(api.getHvacSnapshot).toHaveBeenCalledWith('BLD001')
    expect(api.getLatestHvacIndicators).toHaveBeenCalledWith('BLD001')
    expect(dashboard.snapshot.value).toEqual(snapshot)
    expect(dashboard.latestIndicators.value).toEqual(indicators)
  })

  it('restores the last selection only when it is still accessible', async () => {
    localStorage.setItem('hvac:selectedBuildingId', 'BLD002')
    const api = dependencies()
    api.listAccessibleBuildings.mockResolvedValue([
      building,
      { ...building, buildingId: 'BLD002', buildingName: '二号楼' },
    ])
    const dashboard = useHvacDashboard(api)

    await dashboard.initialize()

    expect(dashboard.selectedBuildingId.value).toBe('BLD002')
  })

  it('keeps successful snapshot data when indicators fail', async () => {
    const api = dependencies()
    api.getLatestHvacIndicators.mockRejectedValue(
      new Error('HVAC 指标时序数据暂不可用，请稍后重试'),
    )
    const dashboard = useHvacDashboard(api)

    await dashboard.initialize()

    expect(dashboard.snapshot.value).toEqual(snapshot)
    expect(dashboard.latestIndicators.value).toBeNull()
    expect(dashboard.indicatorError.value).toContain('暂不可用')
  })

  it('keeps the previous successful section after a later refresh fails', async () => {
    const api = dependencies()
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    api.getHvacSnapshot.mockRejectedValueOnce(new Error('temporary'))

    await dashboard.refresh()

    expect(dashboard.snapshot.value).toEqual(snapshot)
    expect(dashboard.snapshotError.value).toBe('temporary')
  })

  it('does not overlap refresh calls', async () => {
    let resolveSnapshot!: (value: typeof snapshot) => void
    const api = dependencies()
    api.getHvacSnapshot.mockImplementation(
      () => new Promise((resolve) => { resolveSnapshot = resolve }),
    )
    const dashboard = useHvacDashboard(api)
    dashboard.selectedBuildingId.value = 'BLD001'

    const first = dashboard.refresh()
    const second = dashboard.refresh()
    await nextTick()

    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)
    resolveSnapshot(snapshot)
    await Promise.all([first, second])
  })

  it('polls every 30 seconds and stops cleanly', async () => {
    const api = dependencies()
    const dashboard = useHvacDashboard(api, 30_000)
    await dashboard.initialize()
    api.getHvacSnapshot.mockClear()

    dashboard.startPolling()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)

    dashboard.stopPolling()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)
  })
})
