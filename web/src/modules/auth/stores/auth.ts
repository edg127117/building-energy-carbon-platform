import { defineStore } from 'pinia'
import { currentUserApi, loginApi, logoutApi } from '../api/auth'
import type { CurrentUser, LoginCredentials } from '../models/auth'

const tokenKey = 'bec.auth.token'
const userKey = 'bec.auth.user'

function readUser(): CurrentUser | null {
  const value = localStorage.getItem(userKey)
  if (!value) return null
  try {
    const user = JSON.parse(value) as CurrentUser
    return typeof user.uid === 'number' && typeof user.username === 'string' && Array.isArray(user.roles)
      ? user
      : null
  } catch {
    return null
  }
}

/** 浏览器只缓存会话视图；每次受保护导航仍由后端重新确认当前用户。 */
export const useAuthStore = defineStore('platform-auth', {
  state: () => ({
    token: null as string | null,
    user: null as CurrentUser | null,
    restoring: false,
    restored: false,
  }),
  getters: {
    isAuthenticated: state => Boolean(state.token),
    roles: state => state.user?.roles ?? [],
    isPlatformAdmin: state => state.user?.roles.includes('PLATFORM_ADMIN') ?? false,
  },
  actions: {
    hydrate() {
      if (this.token) return
      this.token = localStorage.getItem(tokenKey)
      this.user = readUser()
      if (!this.user) localStorage.removeItem(userKey)
    },
    persist(token: string, user: CurrentUser) {
      this.token = token
      this.user = user
      this.restored = true
      localStorage.setItem(tokenKey, token)
      localStorage.setItem(userKey, JSON.stringify(user))
    },
    clear() {
      this.token = null
      this.user = null
      this.restoring = false
      this.restored = false
      localStorage.removeItem(tokenKey)
      localStorage.removeItem(userKey)
    },
    async login(credentials: LoginCredentials) {
      const result = await loginApi(credentials)
      if (!result.token) throw new Error('AUTHENTICATION_RESPONSE_INVALID')
      this.token = result.token
      localStorage.setItem(tokenKey, result.token)
      try {
        const user = await currentUserApi(result.token)
        this.persist(result.token, user)
      } catch (reason) {
        this.clear()
        throw reason
      }
    },
    async restore() {
      this.hydrate()
      if (!this.token) return false
      if (this.restored && this.user) return true
      this.restoring = true
      try {
        const user = await currentUserApi(this.token)
        this.persist(this.token, user)
        return true
      } catch (reason) {
        this.clear()
        throw reason
      } finally {
        this.restoring = false
      }
    },
    async logout() {
      const token = this.token
      try {
        if (token) await logoutApi(token)
      } finally {
        this.clear()
      }
    },
  },
})
