import { readonly, ref } from 'vue'
import { hvacControlApi } from '../api/hvac-control'

const testOpenControls = ref(false)
const loading = ref(false)
const loaded = ref(false)
let pending: Promise<void> | null = null
let generation = 0

/**
 * HVAC 测试开放例外只来自服务端 access 响应；读取失败或会话切换时默认收回该前端入口，其他菜单不受影响。
 */
export function useHvacControlAccess() {
  async function refresh(): Promise<void> {
    if (pending) return pending
    const requestGeneration = generation
    const operation = (async () => {
      loading.value = true
      try {
        const access = await hvacControlApi.deviceGrantAccess()
        if (requestGeneration === generation) testOpenControls.value = access.testOpenControls === true
      } catch {
        if (requestGeneration === generation) testOpenControls.value = false
      } finally {
        if (requestGeneration === generation) {
          loading.value = false
          loaded.value = true
        }
      }
    })()
    pending = operation
    try {
      await operation
    } finally {
      if (pending === operation) pending = null
    }
  }

  function clear() {
    generation++
    pending = null
    loading.value = false
    loaded.value = false
    testOpenControls.value = false
  }

  return { testOpenControls: readonly(testOpenControls), loading: readonly(loading), loaded: readonly(loaded), refresh, clear }
}
