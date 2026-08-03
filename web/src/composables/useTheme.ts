import { ref, watchEffect, onMounted, computed } from 'vue'

type Theme = 'light' | 'dark'

/**
 * 管理页面明暗主题及其浏览器持久化。
 * 首次挂载优先恢复 localStorage，否则采用系统偏好；每次变化同步根元素 class。
 */
export function useTheme() {
  const theme = ref<Theme>('light')

  /** 读取合法的浏览器主题记忆，缺失时回退操作系统配色偏好。 */
  const getPreferredTheme = (): Theme => {
    const saved = localStorage.getItem('theme') as Theme | null
    if (saved === 'light' || saved === 'dark') return saved
    return window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light'
  }

  /** 同步 HTML 根元素主题 class，并把选择写回 localStorage。 */
  const applyTheme = (t: Theme) => {
    document.documentElement.classList.remove('light', 'dark')
    document.documentElement.classList.add(t)
    localStorage.setItem('theme', t)
  }

  const toggleTheme = () => {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
  }

  onMounted(() => {
    theme.value = getPreferredTheme()
    applyTheme(theme.value)
  })

  watchEffect(() => {
    applyTheme(theme.value)
  })

  return {
    theme,
    toggleTheme,
    isDark: computed(() => theme.value === 'dark'),
  }
}
