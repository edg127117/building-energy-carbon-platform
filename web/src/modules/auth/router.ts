import type { Router } from 'vue-router'
import { configureAuthTransport } from './api/auth'
import { useSession } from './stores/session'

let activeRouter: Router | null = null

/** HTTP 和实时链路确认会话失效后复用唯一会话；导航授权仍由应用守卫统一校验。 */
export function expireBrowserSession(): void {
  useSession().clear()
  if (activeRouter && activeRouter.currentRoute.value.path !== '/login') {
    void activeRouter.replace('/login').catch(() => undefined)
  }
}

export function installPlatformAuthentication(router: Router): void {
  activeRouter = router
  configureAuthTransport({
    getToken: () => useSession().token,
    onUnauthorized: expireBrowserSession,
  })
}
