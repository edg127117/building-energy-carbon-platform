import { createRouter, createWebHashHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import OfficeLayout from '@/app/layouts/office/OfficeLayout.vue'
import MonitorLayout from '@/app/layouts/monitor/MonitorLayout.vue'
import WorkspaceSelection from '@/app/navigation/WorkspaceSelection.vue'
import { pages, authorizedPages } from '@/app/navigation/catalog'
import PendingPage from '@/shared/components/PendingPage.vue'
import NavigationState from '@/app/navigation/NavigationState.vue'
import { LoginPage, useSession } from '@/modules/auth/public'
import { screens } from '@/modules/large-screen/public'
import { t } from '@/locales'

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/systems' },
  { path: '/office', redirect: '/systems' },
  { path: '/login', component: LoginPage, meta: { public: true } },
  { path: '/systems', component: WorkspaceSelection },
  ...(['monitor', 'operations', 'configuration'] as const).map(system => ({
    path: '/' + system, component: system === 'monitor' ? MonitorLayout : OfficeLayout,
    meta: { system, mode: system === 'monitor' ? 'monitor' : 'office' },
    children: [
      { path: '', redirect: '/systems' },
      ...pages.filter(page => page.system === system).map(page => ({
        path: page.path, component: screens.find(screen => screen.path === page.path)?.load ?? PendingPage,
        props: () => ({ title: authorizedPages(useSession().menus).find(item => item.id === page.id)?.title ?? t(page.titleKey) }),
        meta: { system, titleKey: page.titleKey },
      })),
    ],
  })),
  { path: '/403', component: NavigationState, props: { state: 'forbidden' } },
  { path: '/:pathMatch(.*)*', component: NavigationState, props: { state: 'not-found' } },
]

/** 守卫运行于 Pinia 安装后；每次导航校验当前菜单，深链接不能绕过授权。 */
export function createPlatformRouter(history: RouterHistory = createWebHashHistory()) {
  const router = createRouter({ history, routes })
  router.beforeEach(async to => {
    if (to.meta.public) return true
    const session = useSession()
    try { await session.refresh(); authorizedPages(session.menus) } catch {
      session.menus = []; session.failed = true
      if (!session.token) return '/login'
      if (to.path !== '/systems') return { path: '/systems', query: { error: 'access' } }
      return true
    }
    if (!session.user) return '/login'
    if (to.meta.system && !authorizedPages(session.menus).some(page => page.path === to.path)) return '/403'
    return true
  })
  return router
}
