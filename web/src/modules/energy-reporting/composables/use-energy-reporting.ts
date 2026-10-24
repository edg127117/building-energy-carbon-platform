import { ref } from 'vue'
import { energyReportingApi } from '../api/energy-reporting'
import type { ReportingContext } from '../models/reporting'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'

export function useEnergyReporting() {
  const buildingId = ref('')
  const month = ref('')
  const context = ref<ReportingContext | null>(null)
  const contextLoading = ref(false)
  const commandLoading = ref(false)
  const contextError = ref('')
  const commandError = ref('')
  const commandStatus = ref<number | null>(null)
  let contextGeneration = 0
  let commandGeneration = 0
  let activeContextRequest: AbortController | null = null

  async function loadContext(nextBuildingId: string, nextMonth: string): Promise<ReportingContext | null> {
    const targetBuildingId = nextBuildingId.trim()
    const targetMonth = nextMonth.trim()
    const owner = ++contextGeneration
    activeContextRequest?.abort()
    activeContextRequest = null
    buildingId.value = targetBuildingId
    month.value = targetMonth
    context.value = null
    contextError.value = ''
    commandError.value = ''
    commandStatus.value = null
    if (!targetBuildingId || !targetMonth) {
      contextLoading.value = false
      return null
    }
    const controller = new AbortController()
    activeContextRequest = controller
    contextLoading.value = true
    try {
      const response = await energyReportingApi.context(targetBuildingId, targetMonth, controller.signal)
      // 建筑或月份切换后，迟到响应不能覆盖新的路由上下文。
      if (owner !== contextGeneration || buildingId.value !== targetBuildingId || month.value !== targetMonth) return null
      context.value = response
      return response
    } catch (reason) {
      if (owner === contextGeneration && buildingId.value === targetBuildingId && month.value === targetMonth) contextError.value = requestErrorMessage(reason)
      return null
    } finally {
      if (owner === contextGeneration) {
        contextLoading.value = false
        if (activeContextRequest === controller) activeContextRequest = null
      }
    }
  }

  async function refresh(): Promise<ReportingContext | null> {
    return loadContext(buildingId.value, month.value)
  }

  async function execute<T>(command: () => Promise<T>): Promise<T | null> {
    if (commandLoading.value) return null
    const owner = ++commandGeneration
    const targetBuildingId = buildingId.value
    const targetMonth = month.value
    commandLoading.value = true
    commandError.value = ''
    commandStatus.value = null
    try {
      const response = await command()
      if (owner !== commandGeneration || buildingId.value !== targetBuildingId || month.value !== targetMonth) return null
      return response
    } catch (reason) {
      if (owner === commandGeneration && buildingId.value === targetBuildingId && month.value === targetMonth) {
        commandError.value = requestErrorMessage(reason)
        commandStatus.value = requestErrorStatus(reason)
      }
      return null
    } finally {
      if (owner === commandGeneration) commandLoading.value = false
    }
  }

  function cancelContext(): void {
    contextGeneration++
    activeContextRequest?.abort()
    activeContextRequest = null
    contextLoading.value = false
  }

  return {
    buildingId,
    month,
    context,
    contextLoading,
    commandLoading,
    contextError,
    commandError,
    commandStatus,
    loadContext,
    refresh,
    execute,
    cancelContext,
  }
}
