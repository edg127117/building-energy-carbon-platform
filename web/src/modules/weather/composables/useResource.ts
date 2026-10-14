import { onBeforeUnmount, ref, shallowRef } from 'vue'
import { accessDenied, errorCode } from '../api/weather'
import w from '../locales/zh-CN'

/** 切换查询身份立即清空；同身份刷新才保留旧值。序号同时挡住无法取消的迟到响应。 */
export function useResource<T>() {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | undefined
  let generation = 0
  let identity = ''
  function clear() { generation++; controller?.abort(); data.value = null; error.value = ''; loading.value = false; identity = '' }
  async function load(key: string, fetcher: (signal: AbortSignal) => Promise<T>) {
    const current = ++generation
    controller?.abort()
    controller = new AbortController()
    if (identity !== key) data.value = null
    identity = key; loading.value = true; error.value = ''
    try { const result = await fetcher(controller.signal); if (current === generation) data.value = result }
    catch (failure) {
      if (current === generation) {
        if (accessDenied(failure)) data.value = null
        error.value = accessDenied(failure) ? w.accessDenied : w.errors[errorCode(failure) ?? ''] ?? (data.value !== null ? w.stale : w.loadFailed)
      }
    }
    finally { if (current === generation) loading.value = false }
  }
  onBeforeUnmount(clear)
  return { data, loading, error, load, clear }
}
