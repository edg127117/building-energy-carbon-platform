import { defineStore } from 'pinia'
import type { UserInfo } from '@/types/api'
import { loginApi, meApi, registerApi, type LoginReq, type RegisterReq } from '@/api/auth'

type AuthState = {
  token: string | null
  userInfo: UserInfo | null
}

/**
 * 管理登录页、路由守卫和请求拦截器共享的浏览器认证状态。
 *
 * JWT 与用户视图持久化在 localStorage，刷新页面后由路由守卫恢复；前端角色仅用于
 * 导航体验，后端 JWT 过滤器、方法角色和建筑范围校验才是最终安全边界。
 */
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
    /** 从 localStorage 恢复 Token 和用户视图，不向后端重新验证有效期或角色。 */
    hydrateFromStorage() {
      const token = localStorage.getItem('token')
      const userInfoRaw = localStorage.getItem('userInfo')
      this.token = token || null
      this.userInfo = userInfoRaw ? (JSON.parse(userInfoRaw) as UserInfo) : null
    },
    /** 创建账号；注册本身不建立前端登录态，由页面随后显式调用登录。 */
    async register(payload: RegisterReq) {
      await registerApi(payload)
    },
    /**
     * 获取 JWT 后写入 localStorage，再调用 `/auth/me` 保存当前用户和正式角色。
     * `/auth/me` 失败会向页面抛错；Token 已先写入，后续由退出或统一 401 处理清理。
     */
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
    /**
     * 仅清除浏览器内 Token 和用户视图。
     * 该动作没有调用后端 `/auth/logout`，因此不等同于服务端 Token 黑名单退出。
     */
    logout() {
      this.token = null
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    },
  },
})
