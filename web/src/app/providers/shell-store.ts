import { defineStore } from 'pinia'
import { ref } from 'vue'
export const useShellStore = defineStore('platform-shell', () => {
  // 浏览器网络信号不是后端连接健康；这里只用于全局离线提示。
  const offline = ref(false)
  const navigationFailed = ref(false)
  return { offline, navigationFailed }
})
