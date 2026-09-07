import type { NavigationGuard, Router } from 'vue-router'
import { configureAuthTransport } from './api/auth'
import { useAuthStore } from './stores/auth'

const installedRouters = new WeakSet<Router>()
let activeRouter: Router | null = null

function loginLocation(fullPath: string) {
  return { path: '/login', query: { redirect: fullPath } }
}

/** 守卫只改善导航体验；后端仍是令牌、角色和数据范围的权威裁决方。 */
export function createAuthGuard(): NavigationGuard {
  return async to => {
    if (to.meta.public) return true
    const auth = useAuthStore()
    auth.hydrate()
    if (!auth.token) return loginLocation(to.fullPath)
    try {
      if (!auth.user) await auth.restore()
    } catch {
      return loginLocation(to.fullPath)
    }
    if (to.meta.requiresPlatformAdmin && !auth.isPlatformAdmin) return { path: '/403' }
    return true
  }
}

/** 统一处理 HTTP、实时链路等确认失效的会话，跳转责任仍复用已安装的应用路由。 */
export function expireBrowserSession(): void {
  const auth = useAuthStore()
  auth.clear()
  const router = activeRouter
  if (!router) return
  const route = router.currentRoute.value
  if (route.path !== '/login') void router.replace(loginLocation(route.fullPath)).catch(() => undefined)
}

export function installPlatformAuthentication(router: Router): void {
  activeRouter = router
  if (installedRouters.has(router)) return
  installedRouters.add(router)
  configureAuthTransport({
    getToken: () => useAuthStore().token,
    onUnauthorized: expireBrowserSession,
  })
  router.beforeEach(createAuthGuard())
}
