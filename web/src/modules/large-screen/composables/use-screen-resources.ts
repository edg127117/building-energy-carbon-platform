import { onMounted, onScopeDispose, ref, watch } from 'vue'
import { useSession } from '@/modules/auth/public'
import {
  listAssetLedger,
  type AssetLedgerPage,
} from '@/modules/asset-management/public'
import {
  listAccessibleBuildings,
  type Building,
} from '@/modules/dashboard/public'
import {
  screenCarbonQueries,
  type CalculationDetail,
  type CarbonClassificationReport,
} from '@/modules/energy-classification/public'
import {
  beijingMonthWindow,
  hasAvailableCarbonFlow,
  isCurrentCarbonFlowReport,
  previousCompleteBeijingMonth,
  selectScreenMonthlyCarbonResult,
  type ScreenCarbonReason,
  type ScreenCarbonState,
  type ScreenCarbonMonthWindow,
} from '../models/screen-carbon'

export function useScreenResources() {
  const buildings = ref<Building[]>([])
  const buildingId = ref('')
  const month = ref(previousCompleteBeijingMonth())
  const ledger = ref<AssetLedgerPage | null>(null)
  const ledgerLoading = ref(false)
  const ledgerError = ref(false)
  const carbonReport = ref<CarbonClassificationReport | null>(null)
  const carbonDetail = ref<CalculationDetail | null>(null)
  const carbonLoading = ref(false)
  const carbonError = ref(false)
  const carbonState = ref<ScreenCarbonState>('empty')
  const carbonReason = ref<ScreenCarbonReason | null>(null)
  const buildingsLoading = ref(false)
  const buildingsError = ref(false)

  let buildingGeneration = 0
  let resourceGeneration = 0
  let resourceAbort: AbortController | null = null
  let resourceRequest: Promise<void> = Promise.resolve()
  let disposed = false

  function isAccessible(currentBuildingId: string): boolean {
    return buildings.value.some(building => building.buildingId === currentBuildingId)
  }

  function isCurrentResource(
    generation: number,
    currentBuildingId: string,
    currentMonth: string,
    signal: AbortSignal,
  ): boolean {
    return !disposed
      && !signal.aborted
      && generation === resourceGeneration
      && buildingId.value === currentBuildingId
      && month.value === currentMonth
  }

  function clearResources(): void {
    ledger.value = null
    ledgerLoading.value = false
    ledgerError.value = false
    carbonReport.value = null
    carbonDetail.value = null
    carbonLoading.value = false
    carbonError.value = false
    carbonState.value = 'empty'
    carbonReason.value = null
  }

  function invalidateResources(): void {
    resourceGeneration += 1
    resourceAbort?.abort()
    resourceAbort = null
    clearResources()
  }

  function isPlatformAdmin(): boolean {
    return useSession().user?.roles.includes('PLATFORM_ADMIN') === true
  }

  async function loadLedger(
    generation: number,
    currentBuildingId: string,
    currentMonth: string,
    signal: AbortSignal,
  ): Promise<void> {
    if (!isPlatformAdmin()) return
    ledgerLoading.value = true
    try {
      const result = await listAssetLedger({
        buildingId: currentBuildingId,
        page: 1,
        size: 8,
        professionalGroup: 'ALL',
      })
      if (!isCurrentResource(generation, currentBuildingId, currentMonth, signal)) return
      ledger.value = result
    } catch {
      if (!isCurrentResource(generation, currentBuildingId, currentMonth, signal)) return
      ledger.value = null
      ledgerError.value = true
    } finally {
      if (isCurrentResource(generation, currentBuildingId, currentMonth, signal)) {
        ledgerLoading.value = false
      }
    }
  }

  async function loadCarbon(
    generation: number,
    currentBuildingId: string,
    currentMonth: string,
    target: ScreenCarbonMonthWindow,
    signal: AbortSignal,
  ): Promise<void> {
    carbonLoading.value = true
    try {
      const results = await screenCarbonQueries.getMonthlyResults(currentBuildingId, [target.month], signal)
      if (!isCurrentResource(generation, currentBuildingId, currentMonth, signal)) return
      const matches = results.filter(result => result.month === target.month)
      const selection = selectScreenMonthlyCarbonResult(matches.length === 1 ? matches[0] : null, currentBuildingId, target)
      if (!selection.detail) {
        carbonError.value = selection.state === 'error'
        carbonState.value = selection.state
        carbonReason.value = selection.reason
        return
      }

      const detail = selection.detail
      const report = await screenCarbonQueries.getClassificationReport(detail.calculationBatchId, signal)
      if (!isCurrentResource(generation, currentBuildingId, currentMonth, signal)) return
      if (!isCurrentCarbonFlowReport(report, detail.calculationBatchId, currentBuildingId)) {
        carbonError.value = true
        carbonState.value = 'error'
        carbonReason.value = 'REPORT_SCOPE_MISMATCH'
        return
      }
      carbonDetail.value = detail
      carbonReport.value = report
      if (!hasAvailableCarbonFlow(report)) {
        carbonState.value = 'empty'
        carbonReason.value = 'FLOW_UNAVAILABLE'
        return
      }
      carbonState.value = 'ready'
    } catch {
      if (!isCurrentResource(generation, currentBuildingId, currentMonth, signal)) return
      carbonDetail.value = null
      carbonReport.value = null
      carbonError.value = true
      carbonState.value = 'error'
      carbonReason.value = 'REQUEST_FAILED'
    } finally {
      if (isCurrentResource(generation, currentBuildingId, currentMonth, signal)) {
        carbonLoading.value = false
      }
    }
  }

  /** 切换建筑或月份时先失效并清空旧数据，迟到请求只能在所属世代仍有效时写回。 */
  function loadResources(): Promise<void> {
    invalidateResources()
    const currentBuildingId = buildingId.value
    const currentMonth = month.value
    const target = beijingMonthWindow(currentMonth)
    if (!currentBuildingId || !target || !isAccessible(currentBuildingId)) {
      resourceRequest = Promise.resolve()
      return resourceRequest
    }

    const generation = resourceGeneration
    const controller = new AbortController()
    resourceAbort = controller
    const request = Promise.all([
      loadLedger(generation, currentBuildingId, currentMonth, controller.signal),
      loadCarbon(generation, currentBuildingId, currentMonth, target, controller.signal),
    ]).then(() => undefined).finally(() => {
      if (generation === resourceGeneration && resourceAbort === controller) {
        resourceAbort = null
      }
    })
    resourceRequest = request
    return request
  }

  watch([buildingId, month], () => {
    void loadResources()
  }, { flush: 'sync' })

  async function refresh(): Promise<void> {
    if (disposed) return
    const generation = ++buildingGeneration
    buildingsLoading.value = true
    buildingsError.value = false
    try {
      const accessible = await listAccessibleBuildings()
      if (disposed || generation !== buildingGeneration) return
      buildings.value = accessible
      const nextBuildingId = isAccessible(buildingId.value)
        ? buildingId.value
        : accessible[0]?.buildingId ?? ''
      if (nextBuildingId !== buildingId.value) {
        buildingId.value = nextBuildingId
        await resourceRequest
      } else await loadResources()
    } catch {
      if (disposed || generation !== buildingGeneration) return
      buildings.value = []
      buildingsError.value = true
      if (buildingId.value) buildingId.value = ''
      else invalidateResources()
    } finally {
      if (!disposed && generation === buildingGeneration) buildingsLoading.value = false
    }
  }

  onMounted(() => { void refresh() })
  onScopeDispose(() => {
    disposed = true
    buildingGeneration += 1
    invalidateResources()
  })

  return {
    buildings,
    buildingId,
    month,
    ledger,
    ledgerLoading,
    ledgerError,
    carbonReport,
    carbonDetail,
    carbonLoading,
    carbonError,
    carbonState,
    carbonReason,
    buildingsLoading,
    buildingsError,
    refresh,
  }
}
