import { onScopeDispose, ref, shallowRef } from 'vue'
import { getEquipmentReadings, getEquipmentTrendHistory } from '../api/assets'
import type { AssetEquipment, AssetEquipmentReadings, AssetEquipmentTrendHistory } from '../models/assets'
import { requestErrorMessage } from '@/shared/utils/request-error'

/** 真实测点与历史只在右侧查看时请求；主列表始终使用台账聚合返回的 displayPoint。 */
export function useLedgerRuntime() {
  const readings = shallowRef<AssetEquipmentReadings | null>(null)
  const readingsLoading = ref(false)
  const readingsError = ref<string | null>(null)
  const trend = shallowRef<AssetEquipmentTrendHistory | null>(null)
  const trendLoading = ref(false)
  const trendError = ref<string | null>(null)
  let readingGeneration = 0, trendGeneration = 0, disposed = false

  async function loadReadings(equipment: AssetEquipment) {
    if (disposed) return
    const owner = ++readingGeneration
    readings.value = null
    readingsError.value = null
    readingsLoading.value = true
    try {
      const data = await getEquipmentReadings(equipment.equipmentId)
      if (owner !== readingGeneration) return
      if (data.equipmentId !== equipment.equipmentId || data.buildingId !== equipment.buildingId) throw new Error('READING_SCOPE_MISMATCH')
      readings.value = data
    } catch (reason) {
      if (owner === readingGeneration) readingsError.value = requestErrorMessage(reason)
    } finally {
      if (owner === readingGeneration) readingsLoading.value = false
    }
  }
  function clearTrend() { trendGeneration++; trend.value = null; trendLoading.value = false; trendError.value = null }
  async function loadTrend(equipmentId: string) {
    if (disposed) return
    clearTrend()
    const owner = trendGeneration
    trendLoading.value = true
    const endTime = new Date()
    try {
      const data = await getEquipmentTrendHistory(equipmentId, { startTime: new Date(endTime.getTime() - 6 * 3600_000).toISOString(), endTime: endTime.toISOString(), intervalSeconds: 60 })
      if (data.equipmentId !== equipmentId) throw new Error('TREND_SCOPE_MISMATCH')
      if (owner === trendGeneration) trend.value = data
    } catch (reason) { if (owner === trendGeneration) trendError.value = requestErrorMessage(reason) }
    finally { if (owner === trendGeneration) trendLoading.value = false }
  }
  function clear() {
    readingGeneration++
    readings.value = null
    readingsLoading.value = false
    readingsError.value = null
    clearTrend()
  }
  onScopeDispose(() => { disposed = true; clear() })
  return { readings, readingsLoading, readingsError, trend, trendLoading, trendError, loadReadings, loadTrend, clearTrend, clear }
}
