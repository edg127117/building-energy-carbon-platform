import { defineStore } from 'pinia'
import type { UserInfo } from '@/types/api'
import { loginApi, meApi, registerApi, type LoginReq, type RegisterReq } from '@/api/auth'

type AuthState = {
  token: string | null
  userInfo: UserInfo | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: null,
    userInfo: null,
  }),
  getters: {
    roles: (s) => s.userInfo?.roles ?? [],
    isAdmin: (s) => (s.userInfo?.roles ?? []).includes('PLATFORM_ADMIN'),
    isAuthed: (s) => Boolean(s.token),
  },
  actions: {
    hydrateFromStorage() {
      const token = localStorage.getItem('token')
      const userInfoRaw = localStorage.getItem('userInfo')
      this.token = token || null
      this.userInfo = userInfoRaw ? (JSON.parse(userInfoRaw) as UserInfo) : null
    },
    async register(payload: RegisterReq) {
      await registerApi(payload)
    },
    async login(payload: LoginReq) {
      const loginRes = await loginApi(payload)
      const token = loginRes.data?.token
      if (!token) throw new Error('登录失败：未获取到 token')

      this.token = token
      localStorage.setItem('token', token)

      const meRes = await meApi()
      this.userInfo = meRes.data
      localStorage.setItem('userInfo', JSON.stringify(meRes.data))
    },
    logout() {
      this.token = null
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    },
  },
})
