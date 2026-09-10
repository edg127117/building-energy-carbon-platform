import { ref } from 'vue'
import { defineStore } from 'pinia'
import { sessionApi, TransportError } from '../api/session'
import type { GrantedMenu, SessionUser } from '../models/session'

export const useSession = defineStore('platform-session', () => {
  const token = ref<string | null>(null)
  const user = ref<SessionUser | null>(null)
  const menus = ref<GrantedMenu[]>([])
  const failed = ref(false)
  const api = sessionApi(() => token.value)
  let revision = 0
  let pending: Promise<void> | null = null
  function clear() {
    revision++; token.value = null; user.value = null; menus.value = []; pending = null; failed.value = false
    localStorage.removeItem('token'); localStorage.removeItem('userInfo')
  }
  /** 导航前重新读取服务端授权；并发导航合并请求，退出后的旧响应不能恢复会话。 */
  async function refresh() {
    const stored = localStorage.getItem('token')
    if (stored !== token.value) { revision++; user.value = null; menus.value = []; pending = null }
    token.value = stored
    if (!token.value) { clear(); return }
    if (pending) return pending
    const epoch = revision
    const operation = (async () => {
      try {
        const [currentUser, currentMenus] = await Promise.all([api.me(), api.menus()])
        if (epoch !== revision) return
        if (!currentUser?.username || !Array.isArray(currentMenus)) throw new TransportError('request')
        user.value = currentUser; menus.value = currentMenus; failed.value = false
      } catch (error) {
        if (epoch !== revision) return
        user.value = null; menus.value = []; failed.value = true
        if (error instanceof TransportError && error.kind === 'unauthorized') clear()
        throw error
      }
    })()
    pending = operation
    try { await operation } finally { if (pending === operation) pending = null }
  }
  async function login(username: string, password: string) {
    clear()
    const result = await api.login(username, password)
    if (!result?.token) throw new TransportError('request')
    token.value = result.token; localStorage.setItem('token', result.token)
    await refresh()
  }
  /** 服务端注销失败时不假称已安全退出，保留会话以便用户重试。 */
  async function logout() { await api.logout(); clear() }
  return { token, user, menus, failed, refresh, login, logout, clear }
})
