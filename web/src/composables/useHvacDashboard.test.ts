import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type {
  HvacRealtimeClient,
  HvacRealtimeClientEvents,
} from '@/realtime/hvacRealtimeClient'
import type { HvacRealtimeDisconnect } from '@/realtime/hvacRealtimeProtocol'
import type { HvacRealtimeIndicator } from '@/types/hvac'
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
  indicators: [
    {
      indicatorId: 'INDICATOR_WCR_COP_BLD001',
      indicatorCode: 'WCR_COP',
      equipId: 'WCR1',
      minuteStart: 100,
      status: 'SUCCESS',
      value: 5.21,
      unit: '',
      dataQuality: 0,
      formulaVersion: 'WCR_V1',
      reasonCode: null,
      missingInputs: [],
    },
  ],
}

type FakeRealtimeClient = {
  client: HvacRealtimeClient
  connect: ReturnType<typeof vi.fn>
  close: ReturnType<typeof vi.fn>
  events: HvacRealtimeClientEvents
}

function realtimeHarness() {
  const clients: FakeRealtimeClient[] = []
  const expireBrowserSession = vi.fn()
  const createRealtimeClient = vi.fn((events: HvacRealtimeClientEvents) => {
    const connect = vi.fn()
    const close = vi.fn()
    const client = { connect, close }
    clients.push({ client, connect, close, events })
    return client
  })
  return { clients, createRealtimeClient, expireBrowserSession }
}

function dependencies(harness = realtimeHarness()) {
  return {
    listAccessibleBuildings: vi.fn().mockResolvedValue([building]),
    getHvacSnapshot: vi.fn().mockResolvedValue(snapshot),
    getLatestHvacIndicators: vi.fn().mockResolvedValue(indicators),
    getToken: () => 'jwt-value',
    createRealtimeClient: harness.createRealtimeClient,
    expireBrowserSession: harness.expireBrowserSession,
  }
}

function realtimeIndicator(
  overrides: Partial<HvacRealtimeIndicator> = {},
): HvacRealtimeIndicator {
  return {
    indicatorId: 'INDICATOR_WCR_COP_BLD001',
    indicatorCode: 'WCR_COP',
    buildingId: 'BLD001',
    equipId: 'WCR1',
    minuteStart: 120,
    status: 'SUCCESS',
    value: 6.52,
    dataQuality: 0,
    formulaVersion: 'WCR_V1',
    reasonCode: null,
    missingInputs: [],
    ...overrides,
  }
}

async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

function disconnect(
  client: FakeRealtimeClient,
  result: HvacRealtimeDisconnect,
): void {
  client.events.onDisconnected?.(result)
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

  it('loads HTTP before opening one realtime client and retains fallback polling until reconciliation', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api, 30_000)

    await dashboard.initialize()
    dashboard.startRealtime()

    expect(api.getHvacSnapshot.mock.invocationCallOrder[0])
      .toBeLessThan(realtime.clients[0].connect.mock.invocationCallOrder[0])
    expect(dashboard.realtimeState.value).toBe('connecting')
    api.getHvacSnapshot.mockClear()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)
  })

  it('reconciles after SUBSCRIBED and only then stops fallback polling', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api, 30_000)
    await dashboard.initialize()
    api.getHvacSnapshot.mockClear()
    api.getLatestHvacIndicators.mockClear()

    dashboard.startRealtime()
    realtime.clients[0].events.onSubscribed?.('BLD001')
    await flushPromises()

    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)
    expect(api.getLatestHvacIndicators).toHaveBeenCalledTimes(1)
    expect(dashboard.realtimeState.value).toBe('realtime')
    api.getHvacSnapshot.mockClear()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getHvacSnapshot).not.toHaveBeenCalled()
  })

  it('merges a realtime card immediately and coalesces a burst into one HTTP refresh', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()
    realtime.clients[0].events.onSubscribed?.('BLD001')
    await flushPromises()
    api.getHvacSnapshot.mockClear()
    api.getLatestHvacIndicators.mockClear()

    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ value: 6.52 }))
    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ value: 6.61 }))
    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ value: 6.73 }))
    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ value: 6.84 }))

    expect(dashboard.latestIndicators.value?.indicators[0].value).toBe(6.84)
    await vi.advanceTimersByTimeAsync(499)
    expect(api.getHvacSnapshot).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(1)
    expect(api.getLatestHvacIndicators).toHaveBeenCalledTimes(1)
  })

  it('queues exactly one trailing refresh when another realtime burst arrives in flight', async () => {
    let resolveSnapshot!: (value: typeof snapshot) => void
    let resolveIndicators!: (value: typeof indicators) => void
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()
    realtime.clients[0].events.onSubscribed?.('BLD001')
    await flushPromises()
    api.getHvacSnapshot.mockImplementationOnce(
      () => new Promise((resolve) => { resolveSnapshot = resolve }),
    )
    api.getLatestHvacIndicators.mockImplementationOnce(
      () => new Promise((resolve) => { resolveIndicators = resolve }),
    )

    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ minuteStart: 180 }))
    await vi.advanceTimersByTimeAsync(500)
    realtime.clients[0].events.onIndicator?.(realtimeIndicator({ minuteStart: 240 }))
    await vi.advanceTimersByTimeAsync(500)
    resolveSnapshot(snapshot)
    resolveIndicators(indicators)
    await flushPromises()

    // 初始 HTTP 与订阅对账各一次，两个相邻消息窗口只新增当前刷新和一个尾随刷新。
    expect(api.getHvacSnapshot).toHaveBeenCalledTimes(4)
    expect(api.getLatestHvacIndicators).toHaveBeenCalledTimes(4)
  })

  it('uses 1/2/5/10/30-second retry delays after retryable disconnects', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()

    for (const [index, delay] of [1_000, 2_000, 5_000, 10_000, 30_000].entries()) {
      disconnect(realtime.clients[index], { code: 1006, kind: 'network', retry: true })
      expect(dashboard.realtimeState.value).toBe('reconnecting_with_http')
      await vi.advanceTimersByTimeAsync(delay)
      expect(realtime.clients).toHaveLength(index + 2)
    }
  })

  it('resets retry backoff after sixty stable realtime seconds', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()
    disconnect(realtime.clients[0], { code: 1006, kind: 'network', retry: true })
    await vi.advanceTimersByTimeAsync(1_000)
    realtime.clients[1].events.onSubscribed?.('BLD001')
    await flushPromises()

    await vi.advanceTimersByTimeAsync(60_000)
    disconnect(realtime.clients[1], { code: 1006, kind: 'network', retry: true })
    await vi.advanceTimersByTimeAsync(1_000)

    expect(realtime.clients).toHaveLength(3)
  })

  it('keeps HTTP fallback for protocol errors, expires 4401, and stops only the forbidden building for 4403', async () => {
    const protocolRealtime = realtimeHarness()
    const protocolDashboard = useHvacDashboard(dependencies(protocolRealtime))
    await protocolDashboard.initialize()
    protocolDashboard.startRealtime()
    disconnect(protocolRealtime.clients[0], { code: 4400, kind: 'protocol', retry: false })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(protocolDashboard.realtimeState.value).toBe('http_fallback')
    expect(protocolRealtime.clients).toHaveLength(1)

    const authRealtime = realtimeHarness()
    const authDashboard = useHvacDashboard(dependencies(authRealtime))
    await authDashboard.initialize()
    authDashboard.startRealtime()
    disconnect(authRealtime.clients[0], { code: 4401, kind: 'unauthorized', retry: false })
    expect(authRealtime.expireBrowserSession).toHaveBeenCalledTimes(1)

    const forbiddenRealtime = realtimeHarness()
    const forbiddenDashboard = useHvacDashboard(dependencies(forbiddenRealtime))
    await forbiddenDashboard.initialize()
    forbiddenDashboard.startRealtime()
    disconnect(forbiddenRealtime.clients[0], { code: 4403, kind: 'forbidden', retry: false })
    expect(forbiddenDashboard.realtimeState.value).toBe('forbidden')
    expect(forbiddenRealtime.expireBrowserSession).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(forbiddenRealtime.clients).toHaveLength(1)
  })

  it.each([
    { code: 4408, kind: 'timeout' as const },
    { code: 1011, kind: 'server_error' as const },
  ])('uses HTTP fallback and retries close code $code', async ({ code, kind }) => {
    const realtime = realtimeHarness()
    const dashboard = useHvacDashboard(dependencies(realtime))
    await dashboard.initialize()
    dashboard.startRealtime()
    disconnect(realtime.clients[0], { code, kind, retry: true })

    await vi.advanceTimersByTimeAsync(1_000)
    expect(realtime.clients).toHaveLength(2)
  })

  it('isolates old callbacks across a building switch and clears all owned lifecycle resources on stop', async () => {
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    api.listAccessibleBuildings.mockResolvedValue([
      building,
      { ...building, buildingId: 'BLD002', buildingName: '二号楼' },
    ])
    api.getHvacSnapshot.mockImplementation((buildingId) => Promise.resolve({
      ...snapshot,
      buildingId,
    }))
    api.getLatestHvacIndicators.mockImplementation((buildingId) => Promise.resolve({
      ...indicators,
      buildingId,
    }))
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()
    const oldClient = realtime.clients[0]

    await dashboard.selectBuilding('BLD002')
    expect(oldClient.close).toHaveBeenCalledTimes(1)
    expect(realtime.clients).toHaveLength(2)
    oldClient.events.onIndicator?.(realtimeIndicator({ value: 99 }))
    expect(dashboard.latestIndicators.value?.buildingId).toBe('BLD002')

    api.getHvacSnapshot.mockClear()
    dashboard.stopRealtime()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.getHvacSnapshot).not.toHaveBeenCalled()
    expect(realtime.clients[1].close).toHaveBeenCalledTimes(1)
  })

  it('waits for an obsolete in-flight request before loading and subscribing to the next building', async () => {
    let resolveOldSnapshot!: (value: typeof snapshot) => void
    let resolveOldIndicators!: (value: typeof indicators) => void
    const realtime = realtimeHarness()
    const api = dependencies(realtime)
    api.listAccessibleBuildings.mockResolvedValue([
      building,
      { ...building, buildingId: 'BLD002', buildingName: '二号楼' },
    ])
    api.getHvacSnapshot.mockImplementation((buildingId) => Promise.resolve({
      ...snapshot,
      buildingId,
    }))
    api.getLatestHvacIndicators.mockImplementation((buildingId) => Promise.resolve({
      ...indicators,
      buildingId,
    }))
    const dashboard = useHvacDashboard(api)
    await dashboard.initialize()
    dashboard.startRealtime()
    api.getHvacSnapshot.mockImplementationOnce(
      () => new Promise((resolve) => { resolveOldSnapshot = resolve }),
    )
    api.getLatestHvacIndicators.mockImplementationOnce(
      () => new Promise((resolve) => { resolveOldIndicators = resolve }),
    )

    const oldRefresh = dashboard.refresh()
    await nextTick()
    const switchBuilding = dashboard.selectBuilding('BLD002')
    expect(realtime.clients).toHaveLength(1)

    resolveOldSnapshot(snapshot)
    resolveOldIndicators(indicators)
    await oldRefresh
    await switchBuilding

    expect(dashboard.snapshot.value?.buildingId).toBe('BLD002')
    expect(dashboard.latestIndicators.value?.buildingId).toBe('BLD002')
    expect(realtime.clients).toHaveLength(2)
    expect(realtime.clients[1].connect).toHaveBeenCalledWith({
      token: 'jwt-value',
      buildingId: 'BLD002',
    })
  })
})
