import { onMounted, onUnmounted, ref } from 'vue'
export function useFullscreen() {
  const active = ref(false)
  const failed = ref(false)
  const update = () => { active.value = !!document.fullscreenElement }
  onMounted(() => { update(); document.addEventListener('fullscreenchange', update) })
  onUnmounted(() => document.removeEventListener('fullscreenchange', update))
  async function toggle() {
    failed.value = false
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await document.documentElement.requestFullscreen()
      update()
    } catch { failed.value = true }
  }
  return { active, failed, toggle }
}
