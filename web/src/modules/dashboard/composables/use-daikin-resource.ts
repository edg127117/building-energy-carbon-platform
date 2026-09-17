import { onScopeDispose, ref, shallowRef } from 'vue'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'

/** 切建筑、设备或条件后清空旧结果；代际隔离迟到响应，失败不会伪装成空数据。 */
export function useDaikinResource<T>() {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let generation = 0
  function clear() { generation++; data.value = null; loading.value = false; error.value = null }
  async function run(query: () => Promise<T>) {
    const current = ++generation
    loading.value = true; error.value = null; data.value = null
    try { const result = await query(); if (current === generation) data.value = result }
    catch (cause) { if (current === generation) error.value = requestErrorCode(cause) === 'DAIKIN_TEMPERATURE_NOT_BOUND' ? t('dashboard.daikin.temperatureNotBound') : requestErrorMessage(cause) }
    finally { if (current === generation) loading.value = false }
  }
  onScopeDispose(clear)
  return { data, loading, error, run, clear }
}
