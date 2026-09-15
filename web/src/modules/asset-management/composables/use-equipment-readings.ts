import { ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { getEquipmentReadings } from '../api/assets'
import type { AssetEquipmentReadings } from '../models/assets'

/** 设备切换或关闭读数窗口后，旧请求不得覆盖新设备的读数与错误状态。 */
export function useEquipmentReadings() {
  const readings = ref<AssetEquipmentReadings | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let generation = 0

  async function load(equipmentId: string) {
    const owner = ++generation
    readings.value = null
    loading.value = true
    error.value = null
    try {
      const result = await getEquipmentReadings(equipmentId)
      if (owner === generation) readings.value = result
      return result
    } catch (reason) {
      if (owner === generation) error.value = requestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  function clear() {
    ++generation
    readings.value = null
    loading.value = false
    error.value = null
  }

  return { readings, loading, error, load, clear }
}
