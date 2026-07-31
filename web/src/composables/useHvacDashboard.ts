import { computed, ref } from 'vue'
import {
  getHvacSnapshot,
  getLatestHvacIndicators,
  listAccessibleBuildings,
} from '@/api/hvac'
import {
  buildIndicatorViews,
  buildPointViews,
  calculatePointCoverage,
} from '@/domain/hvacDashboard'
import type {
  Building,
  HvacLatestResponse,
  HvacSnapshotResponse,
} from '@/types/hvac'

const SELECTED_BUILDING_KEY = 'hvac:selectedBuildingId'

export type HvacDashboardDependencies = {
  listAccessibleBuildings: () => Promise<Building[]>
  getHvacSnapshot: (buildingId: string) => Promise<HvacSnapshotResponse>
  getLatestHvacIndicators: (
    buildingId: string,
  ) => Promise<HvacLatestResponse>
}

const defaultDependencies: HvacDashboardDependencies = {
  listAccessibleBuildings,
  getHvacSnapshot,
  getLatestHvacIndicators,
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试'
}

/**
 * 编排 HVAC 大屏的建筑选择、两类独立数据请求和刷新生命周期。
 *
 * 快照与指标互不清空，单个请求失败时保留该部分最后一次成功数据，页面才能
 * 如实提示过期状态，而不会把外部时序库的短暂故障伪装成“没有历史数据”。
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

  let refreshPromise: Promise<void> | null = null
  let pollingTimer: number | null = null

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

  async function refresh(): Promise<void> {
    if (!selectedBuildingId.value) return
    if (refreshPromise) return refreshPromise

    const buildingId = selectedBuildingId.value
    refreshing.value = true
    refreshPromise = (async () => {
      const [snapshotResult, indicatorResult] = await Promise.allSettled([
        dependencies.getHvacSnapshot(buildingId),
        dependencies.getLatestHvacIndicators(buildingId),
      ])

      if (buildingId !== selectedBuildingId.value) return

      if (snapshotResult.status === 'fulfilled') {
        snapshot.value = snapshotResult.value
        snapshotUpdatedAt.value = Date.now()
        snapshotError.value = null
      } else {
        snapshotError.value = errorMessage(snapshotResult.reason)
      }

      if (indicatorResult.status === 'fulfilled') {
        latestIndicators.value = indicatorResult.value
        indicatorUpdatedAt.value = Date.now()
        indicatorError.value = null
      } else {
        indicatorError.value = errorMessage(indicatorResult.reason)
      }
    })()

    try {
      await refreshPromise
    } finally {
      refreshPromise = null
      refreshing.value = false
    }
  }

  async function selectBuilding(buildingId: string): Promise<void> {
    if (!buildings.value.some((item) => item.buildingId === buildingId)) {
      return
    }
    selectedBuildingId.value = buildingId
    localStorage.setItem(SELECTED_BUILDING_KEY, buildingId)
    snapshot.value = null
    latestIndicators.value = null
    snapshotError.value = null
    indicatorError.value = null
    await refresh()
  }

  async function initialize(): Promise<void> {
    initializing.value = true
    buildingError.value = null
    try {
      buildings.value = await dependencies.listAccessibleBuildings()
      const remembered = localStorage.getItem(SELECTED_BUILDING_KEY)
      const initial =
        buildings.value.find((item) => item.buildingId === remembered) ??
        buildings.value[0] ??
        null
      selectedBuildingId.value = initial?.buildingId ?? null
      if (initial) {
        localStorage.setItem(SELECTED_BUILDING_KEY, initial.buildingId)
        await refresh()
      }
    } catch (error) {
      buildingError.value = errorMessage(error)
    } finally {
      initializing.value = false
    }
  }

  /**
   * 后端当前提供分钟级快照，本期尚未接入 WebSocket；30 秒轮询既能及时显示
   * 新分钟数据，也避免浏览器高频重复查询时序库。
   */
  function startPolling(): void {
    if (pollingTimer !== null) return
    pollingTimer = window.setInterval(() => {
      void refresh()
    }, refreshIntervalMs)
  }

  function stopPolling(): void {
    if (pollingTimer === null) return
    window.clearInterval(pollingTimer)
    pollingTimer = null
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
    initialize,
    selectBuilding,
    refresh,
    startPolling,
    stopPolling,
  }
}
