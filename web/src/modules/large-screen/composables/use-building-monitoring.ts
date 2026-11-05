import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { readBuildingDeviceSummary, readBuildingRuntimeRanking, type BuildingDeviceSummary, type BuildingRuntimeRanking } from '../api/building-monitoring'
import { mapBuildingDeviceSummary, mapBuildingRuntimeRanking, validateBuildingDeviceSummary, validateBuildingRuntimeRanking } from '../models/building-monitoring'

const REFRESH_MS = 30_000
const VISIBLE_MODES = new Set(['fan', 'cooling', 'heating'])
const VISIBLE_SPEEDS = new Set(['low', 'middle', 'high', 'automatic'])
const beijingMonth = (time: number) => new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
}).format(time)
function nextBeijingMonthStart(time: number): number {
  const match = /^(\d{4})-(\d{2})$/.exec(beijingMonth(time))!
  return Date.UTC(Number(match[1]), Number(match[2]), 1) - 8 * 60 * 60 * 1000
}

export function useBuildingMonitoring(buildingId: Ref<string>) {
  const now = ref(Date.now())
  const summaryResponse = ref<BuildingDeviceSummary>()
  const rankingResponse = ref<BuildingRuntimeRanking>()
  const summaryFailed = ref(false), rankingFailed = ref(false)
  const month = computed(() => beijingMonth(now.value))
  let generation = 0, disposed = false
  let summaryAbort: AbortController | undefined, rankingAbort: AbortController | undefined
  let summaryPending = false, rankingPending = false
  let boundaryTimer: ReturnType<typeof setTimeout> | undefined

  const valid = (current: number, id: string) => !disposed && generation === current && buildingId.value === id
  async function refreshSummary(current: number, id: string) {
    if (!id || summaryPending) return
    summaryPending = true
    const controller = new AbortController(); summaryAbort = controller
    try {
      const result = await readBuildingDeviceSummary(id, controller.signal)
      validateBuildingDeviceSummary(result, id)
      if (valid(current, id)) { now.value = Date.now(); summaryResponse.value = result; summaryFailed.value = false }
    } catch {
      if (valid(current, id)) summaryFailed.value = true
    } finally {
      if (summaryAbort === controller) { summaryAbort = undefined; summaryPending = false }
    }
  }
  async function refreshRanking(current: number, id: string, requestedMonth: string) {
    if (!id || rankingPending) return
    rankingPending = true
    const controller = new AbortController(); rankingAbort = controller
    try {
      const result = await readBuildingRuntimeRanking(id, requestedMonth, controller.signal)
      validateBuildingRuntimeRanking(result, id, requestedMonth)
      if (valid(current, id) && month.value === requestedMonth) { rankingResponse.value = result; rankingFailed.value = false }
    } catch {
      if (valid(current, id) && month.value === requestedMonth) rankingFailed.value = true
    } finally {
      if (rankingAbort === controller) { rankingAbort = undefined; rankingPending = false }
    }
  }
  function refreshAll(current = generation, id = buildingId.value) {
    void refreshSummary(current, id)
    void refreshRanking(current, id, month.value)
  }
  watch(buildingId, id => {
    generation++
    summaryAbort?.abort(); rankingAbort?.abort()
    summaryAbort = undefined; rankingAbort = undefined
    summaryPending = false; rankingPending = false
    summaryResponse.value = undefined; rankingResponse.value = undefined
    summaryFailed.value = false; rankingFailed.value = false
    refreshAll(generation, id)
  }, { immediate: true, flush: 'sync' })
  watch(month, value => {
    rankingAbort?.abort(); rankingAbort = undefined; rankingPending = false
    rankingResponse.value = undefined; rankingFailed.value = false
    void refreshRanking(generation, buildingId.value, value)
  }, { flush: 'sync' })
  // Independent in-flight guards keep the two endpoints isolated; transport timeout remains below the polling interval.
  const timer = setInterval(() => {
    now.value = Date.now()
    refreshAll()
  }, REFRESH_MS)
  // The card must expire at the backend deadline and invalidate last-month ranking at the Shanghai month boundary.
  function scheduleNextBoundary() {
    if (boundaryTimer !== undefined) clearTimeout(boundaryTimer)
    const current = Date.now()
    if (now.value !== current) now.value = current
    const candidates = [nextBeijingMonthStart(current)]
    const expiry = summaryResponse.value?.expiresAt
    if (expiry !== undefined && expiry > current) candidates.push(expiry)
    const next = Math.min(...candidates)
    boundaryTimer = setTimeout(() => {
      now.value = Date.now()
      scheduleNextBoundary()
    }, Math.max(0, Math.min(next - current, 2_147_000_000)))
  }
  watch([summaryResponse, month], scheduleNextBoundary, { flush: 'sync' })
  scheduleNextBoundary()
  onScopeDispose(() => {
    disposed = true; generation++
    clearInterval(timer); if (boundaryTimer !== undefined) clearTimeout(boundaryTimer)
    summaryAbort?.abort(); rankingAbort?.abort()
  })

  return {
    month,
    devices: computed(() => summaryResponse.value && now.value < summaryResponse.value.expiresAt
      ? mapBuildingDeviceSummary(summaryResponse.value) : undefined),
    updatedAt: computed(() => summaryResponse.value && now.value < summaryResponse.value.expiresAt
      ? summaryResponse.value.asOf : undefined),
    summaryLastSuccess: computed(() => summaryResponse.value?.asOf),
    summaryDiagnostics: computed(() => summaryResponse.value ? {
      modeExtras: Object.entries(summaryResponse.value.modes.counts).filter(([key]) => !VISIBLE_MODES.has(key)),
      speedExtras: Object.entries(summaryResponse.value.speeds.counts).filter(([key]) => !VISIBLE_SPEEDS.has(key)),
      unknownReasons: Object.entries(summaryResponse.value.unknownReasons),
      modeReasons: Object.entries(summaryResponse.value.modes.reasons),
      speedReasons: Object.entries(summaryResponse.value.speeds.reasons),
      modeObserved: summaryResponse.value.modes.observed,
      speedObserved: summaryResponse.value.speeds.observed,
    } : undefined),
    summaryExpired: computed(() => !!summaryResponse.value && now.value >= summaryResponse.value.expiresAt),
    summaryFailed,
    runtimeRanking: computed(() => rankingResponse.value ? mapBuildingRuntimeRanking(rankingResponse.value) : undefined),
    rankingContext: computed(() => rankingResponse.value ? {
      month: rankingResponse.value.month, asOf: rankingResponse.value.asOf,
      periodStart: rankingResponse.value.periodStart, periodEnd: rankingResponse.value.periodEnd,
      elapsedMillis: rankingResponse.value.elapsedMillis, source: rankingResponse.value.source,
      rankedDevices: rankingResponse.value.rankedDevices, totalDevices: rankingResponse.value.totalDevices,
      excludedReasons: rankingResponse.value.excludedReasons,
    } : undefined),
    rankingFailed,
  }
}
