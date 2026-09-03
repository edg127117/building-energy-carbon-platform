import { onMounted, onUnmounted, ref } from 'vue'
import { useShellStore } from './shell-store'
export function useShellRuntime() {
  const shell = useShellStore()
  const now = ref(new Date())
  let clock: ReturnType<typeof setInterval> | undefined
  const updateNetwork = () => { shell.offline = !navigator.onLine }
  onMounted(() => {
    updateNetwork()
    window.addEventListener('online', updateNetwork)
    window.addEventListener('offline', updateNetwork)
    // 时钟只更新本机显示，不是业务数据刷新，也不触发页面轮播。
    clock = setInterval(() => { now.value = new Date() }, 1000)
  })
  onUnmounted(() => {
    clearInterval(clock)
    window.removeEventListener('online', updateNetwork)
    window.removeEventListener('offline', updateNetwork)
  })
  return { now }
}
