import { computed, ref } from 'vue'
import { expireBrowserSession } from '@/auth/session'
import {
  getHvacSnapshot,
  getLatestHvacIndicators,
  listAccessibleBuildings,
} from '@/api/hvac'
import {
  buildIndicatorViews,
  buildPointViews,
  calculatePointCoverage,
  mergeRealtimeIndicator,
} from '@/domain/hvacDashboard'
import {
  createHvacRealtimeClient,
  type HvacRealtimeClient,
  type HvacRealtimeClientEvents,
} from '@/realtime/hvacRealtimeClient'
import type {
  Building,
  HvacLatestResponse,
  HvacSnapshotResponse,
} from '@/types/hvac'

const SELECTED_BUILDING_KEY = 'hvac:selectedBuildingId'
const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 30_000] as const
const REALTIME_STABLE_RESET_MS = 60_000
const REALTIME_RECONCILIATION_DELAY_MS = 500

export type HvacRealtimeState =
  | 'connecting'
  | 'realtime'
  | 'reconnecting_with_http'
  | 'http_fallback'
  | 'forbidden'

type RefreshOutcome = {
  snapshotSucceeded: boolean
  indicatorsSucceeded: boolean
  complete: boolean
  stale: boolean
}

export type HvacDashboardDependencies = {
  listAccessibleBuildings: () => Promise<Building[]>
  getHvacSnapshot: (buildingId: string) => Promise<HvacSnapshotResponse>
  getLatestHvacIndicators: (
    buildingId: string,
  ) => Promise<HvacLatestResponse>
  getToken?: () => string | null
  createRealtimeClient?: (
    events: HvacRealtimeClientEvents,
  ) => HvacRealtimeClient
  expireBrowserSession?: () => void
}

const defaultDependencies: HvacDashboardDependencies = {
  listAccessibleBuildings,
  getHvacSnapshot,
  getLatestHvacIndicators,
  getToken: () => localStorage.getItem('token'),
  createRealtimeClient: (events) => createHvacRealtimeClient({ events }),
  expireBrowserSession,
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试'
}

function emptyRefreshOutcome(): RefreshOutcome {
  return {
    snapshotSucceeded: false,
    indicatorsSucceeded: false,
    complete: false,
    stale: true,
  }
}

/**
 * 编排 HVAC 大屏的建筑选择、HTTP 权威状态与实时连接生命周期。
 *
 * HTTP 仍负责已授权的 19 测点完整快照和全部最新指标；WebSocket 只提前合并四项指标，
 * 并通过合并后的 HTTP 对账恢复断线遗漏。代际编号隔离切楼、卸载和迟到回调，避免旧建筑
 * 的 Socket、定时器或请求覆盖当前页面。
 */
export function useHvacDashboard(
  dependencies: HvacDashboardDependencies = defaultDependencies,
  refreshIntervalMs = 30_000,
) {
  const buildings = ref<Building[]>([])
  const selectedBuildingId = ref<string | null>(null)
  const snapshot = ref<HvacSnapshotResponse | null>(null)
  const latestIndicators = ref<HvacLatestResponse | null>(null)
  const initializing = ref(false)
  const refreshing = ref(false)
  const buildingError = ref<string | null>(null)
  const snapshotError = ref<string | null>(null)
  const indicatorError = ref<string | null>(null)
  const snapshotUpdatedAt = ref<number | null>(null)
  const indicatorUpdatedAt = ref<number | null>(null)
  const realtimeState = ref<HvacRealtimeState>('http_fallback')

  let refreshPromise: Promise<RefreshOutcome> | null = null
  let refreshGeneration: number | null = null
  let refreshBuildingId: string | null = null
  let refreshRequestedAfterFlight = false
  let queuedReconciliationGeneration: number | null = null
  let fallbackPollingTimer: number | null = null
  let reconnectTimer: number | null = null
  let stableConnectionTimer: number | null = null
  let trailingReconciliationTimer: number | null = null
  let realtimeClient: HvacRealtimeClient | null = null
  let realtimeLifecycleActive = false
  let lifecycleGeneration = 0
  let retryAttempt = 0
  let connectionAttemptIsReconnect = false
  let reconciliationGeneration: number | null = null
  let subscribedGeneration: number | null = null

  const selectedBuilding = computed(
    () =>
      buildings.value.find(
        (item) => item.buildingId === selectedBuildingId.value,
      ) ?? null,
  )
  const pointViews = computed(() => buildPointViews(snapshot.value))
  const indicatorViews = computed(() =>
    buildIndicatorViews(latestIndicators.value),
  )
  const coveragePercent = computed(() =>
    calculatePointCoverage(pointViews.value),
  )

  function isCurrentGeneration(
    generation: number,
    buildingId: string | null = selectedBuildingId.value,
  ): boolean {
    return generation === lifecycleGeneration
      && buildingId !== null
      && buildingId === selectedBuildingId.value
  }

  function clearFallbackPolling(): void {
    if (fallbackPollingTimer === null) return
    window.clearInterval(fallbackPollingTimer)
    fallbackPollingTimer = null
  }

  function startFallbackPolling(): void {
    if (fallbackPollingTimer !== null) return
    fallbackPollingTimer = window.setInterval(() => {
      void refresh()
    }, refreshIntervalMs)
  }

  function clearReconnectTimer(): void {
    if (reconnectTimer === null) return
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  function clearStableConnectionTimer(): void {
    if (stableConnectionTimer === null) return
    window.clearTimeout(stableConnectionTimer)
    stableConnectionTimer = null
  }

  function clearTrailingReconciliation(): void {
    if (trailingReconciliationTimer === null) return
    window.clearTimeout(trailingReconciliationTimer)
    trailingReconciliationTimer = null
  }

  function closeRealtimeClient(): void {
    if (realtimeClient === null) return
    realtimeClient.close()
    realtimeClient = null
  }

  function clearRealtimeResources(): void {
    clearFallbackPolling()
    clearReconnectTimer()
    clearStableConnectionTimer()
    clearTrailingReconciliation()
    closeRealtimeClient()
    refreshRequestedAfterFlight = false
    queuedReconciliationGeneration = null
    reconciliationGeneration = null
    subscribedGeneration = null
  }

  /**
   * 并行刷新同一建筑的测点快照和最新指标。
   *
   * `refreshPromise` 是所有手动、轮询和实时对账的单飞屏障；两部分可独立保留最后成功值，
   * 但仅两者均成功的同代响应才可确认实时通道已完成权威对账。
   */
  async function refresh(): Promise<RefreshOutcome> {
    if (!selectedBuildingId.value) return emptyRefreshOutcome()
    const buildingId = selectedBuildingId.value
    const generation = lifecycleGeneration
    if (refreshPromise) {
      if (refreshGeneration === generation && refreshBuildingId === buildingId) {
        return refreshPromise
      }
      // 无法取消的旧 HTTP 请求先自然结束，再为当前代启动一次新查询，不能把旧 Promise 当作新楼首屏。
      return refreshPromise.then(() => {
        if (!isCurrentGeneration(generation, buildingId)) {
          return emptyRefreshOutcome()
        }
        return refresh()
      })
    }

    refreshing.value = true
    const currentRefresh = (async (): Promise<RefreshOutcome> => {
      const [snapshotResult, indicatorResult] = await Promise.allSettled([
        dependencies.getHvacSnapshot(buildingId),
        dependencies.getLatestHvacIndicators(buildingId),
      ])

      if (!isCurrentGeneration(generation, buildingId)) {
        return emptyRefreshOutcome()
      }

      const snapshotSucceeded = snapshotResult.status === 'fulfilled'
        && snapshotResult.value.buildingId === buildingId
      const indicatorsSucceeded = indicatorResult.status === 'fulfilled'
        && indicatorResult.value.buildingId === buildingId

      if (snapshotSucceeded && snapshotResult.status === 'fulfilled') {
        snapshot.value = snapshotResult.value
        snapshotUpdatedAt.value = Date.now()
        snapshotError.value = null
      } else if (snapshotResult.status === 'rejected') {
        snapshotError.value = errorMessage(snapshotResult.reason)
      } else {
        snapshotError.value = '测点快照返回的建筑范围不一致'
      }

      if (indicatorsSucceeded && indicatorResult.status === 'fulfilled') {
        latestIndicators.value = indicatorResult.value
        indicatorUpdatedAt.value = Date.now()
        indicatorError.value = null
      } else if (indicatorResult.status === 'rejected') {
        indicatorError.value = errorMessage(indicatorResult.reason)
      } else {
        indicatorError.value = '性能指标返回的建筑范围不一致'
      }

      return {
        snapshotSucceeded,
        indicatorsSucceeded,
        complete: snapshotSucceeded && indicatorsSucceeded,
        stale: false,
      }
    })()
    refreshPromise = currentRefresh
    refreshGeneration = generation
    refreshBuildingId = buildingId

    try {
      const outcome = await currentRefresh
      settleReconciliation(generation, outcome)
      return outcome
    } finally {
      if (refreshPromise === currentRefresh) {
        refreshPromise = null
        refreshGeneration = null
        refreshBuildingId = null
        refreshing.value = false
        const queuedGeneration = queuedReconciliationGeneration
        const shouldRunQueuedRefresh = refreshRequestedAfterFlight
        refreshRequestedAfterFlight = false
        queuedReconciliationGeneration = null
        if (shouldRunQueuedRefresh && queuedGeneration !== null) {
          requestReconciliation(queuedGeneration)
        }
      }
    }
  }

  function scheduleStableBackoffReset(generation: number): void {
    clearStableConnectionTimer()
    stableConnectionTimer = window.setTimeout(() => {
      if (!isCurrentGeneration(generation) || realtimeState.value !== 'realtime') {
        return
      }
      retryAttempt = 0
      stableConnectionTimer = null
    }, REALTIME_STABLE_RESET_MS)
  }

  function settleReconciliation(
    generation: number,
    outcome: RefreshOutcome,
  ): void {
    if (outcome.stale || reconciliationGeneration !== generation) return
    if (outcome.complete && subscribedGeneration === generation) {
      reconciliationGeneration = null
      clearFallbackPolling()
      realtimeState.value = 'realtime'
      scheduleStableBackoffReset(generation)
      return
    }

    if (
      subscribedGeneration !== generation
      && realtimeState.value === 'reconnecting_with_http'
    ) {
      startFallbackPolling()
      return
    }

    realtimeState.value = connectionAttemptIsReconnect
      ? 'reconnecting_with_http'
      : 'http_fallback'
    startFallbackPolling()
  }

  /** 请求一次权威对账；正在刷新时只保留一个尾随请求，避免四条指标产生并行 HTTP。 */
  function requestReconciliation(generation: number): void {
    if (!isCurrentGeneration(generation)) return
    reconciliationGeneration = generation
    if (refreshPromise) {
      refreshRequestedAfterFlight = true
      queuedReconciliationGeneration = generation
      return
    }
    void refresh()
  }

  function scheduleTrailingReconciliation(generation: number): void {
    clearTrailingReconciliation()
    trailingReconciliationTimer = window.setTimeout(() => {
      trailingReconciliationTimer = null
      requestReconciliation(generation)
    }, REALTIME_RECONCILIATION_DELAY_MS)
  }

  function scheduleReconnect(generation: number): void {
    if (!realtimeLifecycleActive || reconnectTimer !== null) return
    const delay = RECONNECT_DELAYS_MS[
      Math.min(retryAttempt, RECONNECT_DELAYS_MS.length - 1)
    ]
    retryAttempt += 1
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      if (!isCurrentGeneration(generation) || !realtimeLifecycleActive) return
      connectRealtime(generation, true)
    }, delay)
  }

  function handleRealtimeDisconnect(
    generation: number,
    buildingId: string,
    client: HvacRealtimeClient,
    disconnect: Parameters<NonNullable<HvacRealtimeClientEvents['onDisconnected']>>[0],
  ): void {
    if (!realtimeLifecycleActive || !isCurrentGeneration(generation, buildingId)) {
      return
    }
    if (realtimeClient === client) realtimeClient = null
    if (subscribedGeneration === generation) subscribedGeneration = null
    clearStableConnectionTimer()
    clearTrailingReconciliation()

    if (disconnect.kind === 'unauthorized') {
      dependencies.expireBrowserSession?.()
      stopRealtime()
      return
    }

    if (disconnect.kind === 'forbidden') {
      realtimeLifecycleActive = false
      clearReconnectTimer()
      clearFallbackPolling()
      reconciliationGeneration = null
      subscribedGeneration = null
      realtimeState.value = 'forbidden'
      return
    }

    startFallbackPolling()
    requestReconciliation(generation)
    if (disconnect.retry) {
      realtimeState.value = 'reconnecting_with_http'
      scheduleReconnect(generation)
    } else {
      realtimeState.value = 'http_fallback'
    }
  }

  function connectRealtime(
    generation: number,
    reconnecting: boolean,
  ): void {
    const buildingId = selectedBuildingId.value
    const token = dependencies.getToken?.() ?? localStorage.getItem('token')
    if (!isCurrentGeneration(generation, buildingId) || !realtimeLifecycleActive) {
      return
    }
    if (!token) {
      realtimeState.value = 'http_fallback'
      startFallbackPolling()
      return
    }

    closeRealtimeClient()
    connectionAttemptIsReconnect = reconnecting
    if (!reconnecting) realtimeState.value = 'connecting'
    const events: HvacRealtimeClientEvents = {}
    const client = dependencies.createRealtimeClient?.(events)
      ?? createHvacRealtimeClient({ events })
    realtimeClient = client
    events.onSubscribed = (subscribedBuildingId) => {
      if (
        !isCurrentGeneration(generation, buildingId)
        || subscribedBuildingId !== buildingId
        || realtimeClient !== client
      ) {
        return
      }
      subscribedGeneration = generation
      requestReconciliation(generation)
    }
    events.onIndicator = (indicator) => {
      if (
        !isCurrentGeneration(generation, buildingId)
        || realtimeClient !== client
      ) {
        return
      }
      const merged = mergeRealtimeIndicator(
        latestIndicators.value,
        indicator,
        buildingId,
      )
      if (merged === latestIndicators.value) return
      latestIndicators.value = merged
      indicatorUpdatedAt.value = Date.now()
      scheduleTrailingReconciliation(generation)
    }
    events.onDisconnected = (disconnect) => {
      handleRealtimeDisconnect(generation, buildingId, client, disconnect)
    }
    client.connect({ token, buildingId })
  }

  /**
   * 在首屏 HTTP 查询完成后启动唯一的实时生命周期。
   *
   * 先保留 30 秒 HTTP 保障，只有收到订阅确认并成功取得同代完整对账后才停止轮询；重复
   * 调用不创建第二条 Socket、轮询或重连计时器。
   */
  function startRealtime(): void {
    if (realtimeLifecycleActive) return
    realtimeLifecycleActive = true
    const generation = ++lifecycleGeneration
    retryAttempt = 0
    if (!selectedBuildingId.value) {
      realtimeState.value = 'http_fallback'
      return
    }
    startFallbackPolling()
    realtimeState.value = 'connecting'
    connectRealtime(generation, false)
  }

  /** 页面卸载、登录失效或建筑切换前统一释放实时资源，主动关闭不得触发旧连接重连。 */
  function stopRealtime(): void {
    realtimeLifecycleActive = false
    lifecycleGeneration += 1
    clearRealtimeResources()
    realtimeState.value = 'http_fallback'
  }

  /**
   * 只接受已授权建筑，并在切换时先失效旧代再加载新建筑。
   *
   * 旧 Socket 的 `close/message` 和在途 HTTP 响应都因代际不匹配被丢弃；新建筑先完成 HTTP
   * 当前状态，再重新建立首帧认证订阅，确保浏览器从不把旧楼数据写到新楼页面。
   */
  async function selectBuilding(buildingId: string): Promise<void> {
    if (!buildings.value.some((item) => item.buildingId === buildingId)) return
    if (buildingId === selectedBuildingId.value) {
      await refresh()
      return
    }

    const restartRealtime = realtimeLifecycleActive
    if (restartRealtime) stopRealtime()
    else lifecycleGeneration += 1

    selectedBuildingId.value = buildingId
    localStorage.setItem(SELECTED_BUILDING_KEY, buildingId)
    snapshot.value = null
    latestIndicators.value = null
    snapshotError.value = null
    indicatorError.value = null
    snapshotUpdatedAt.value = null
    indicatorUpdatedAt.value = null
    await refresh()
    if (restartRealtime) startRealtime()
  }

  /** 加载授权建筑后完成首屏 HTTP 查询；实时连接由页面显式在该步骤之后启动。 */
  async function initialize(): Promise<void> {
    stopRealtime()
    initializing.value = true
    buildingError.value = null
    snapshot.value = null
    latestIndicators.value = null
    snapshotError.value = null
    indicatorError.value = null
    snapshotUpdatedAt.value = null
    indicatorUpdatedAt.value = null
    const generation = lifecycleGeneration
    try {
      buildings.value = await dependencies.listAccessibleBuildings()
      if (generation !== lifecycleGeneration) return
      const remembered = localStorage.getItem(SELECTED_BUILDING_KEY)
      const initial = buildings.value.find(
        (item) => item.buildingId === remembered,
      ) ?? buildings.value[0] ?? null
      selectedBuildingId.value = initial?.buildingId ?? null
      if (initial) {
        localStorage.setItem(SELECTED_BUILDING_KEY, initial.buildingId)
        await refresh()
      }
    } catch (error) {
      buildingError.value = errorMessage(error)
    } finally {
      if (generation === lifecycleGeneration) initializing.value = false
    }
  }

  return {
    buildings,
    selectedBuildingId,
    selectedBuilding,
    snapshot,
    latestIndicators,
    pointViews,
    indicatorViews,
    coveragePercent,
    initializing,
    refreshing,
    buildingError,
    snapshotError,
    indicatorError,
    snapshotUpdatedAt,
    indicatorUpdatedAt,
    realtimeState,
    initialize,
    selectBuilding,
    refresh,
    startRealtime,
    stopRealtime,
  }
}
