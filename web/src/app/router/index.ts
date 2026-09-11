import { createRouter, createWebHashHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import OfficeLayout from '@/app/layouts/office/OfficeLayout.vue'
import MonitorLayout from '@/app/layouts/monitor/MonitorLayout.vue'
import WorkspaceSelection from '@/app/navigation/WorkspaceSelection.vue'
import { pages, authorizedPages } from '@/app/navigation/catalog'
import PendingPage from '@/shared/components/PendingPage.vue'
import NavigationState from '@/app/navigation/NavigationState.vue'
import { LoginPage, PasswordSetupPage, useSession } from '@/modules/auth/public'
import { routes as accessRoutes } from '@/modules/access-control/public'
import { routes as assetRoutes } from '@/modules/asset-management/public'
import { routes as deviceRoutes } from '@/modules/device-onboarding/public'
import { routes as dashboardRoutes } from '@/modules/dashboard/public'
import { routes as trendRoutes } from '@/modules/trend-analysis/public'
import { screens } from '@/modules/large-screen/public'
import { t } from '@/locales'

// 迁移的是页面能力而非旧外壳；八项后台一对一落位，暖通与趋势只绑定各自已批准的入口。
const migratedRoutes = [...accessRoutes, ...assetRoutes, ...deviceRoutes]
function businessRoute(page: (typeof pages)[number]) {
  if (page.path === '/operations/realtime/hvac') return dashboardRoutes[0]
  if (page.path === '/operations/energy/trend') return trendRoutes[0]
  return page.legacyPath ? migratedRoutes.find(route => route.path === page.legacyPath) : undefined
}

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/systems' },
  { path: '/office', redirect: '/systems' },
  { path: '/login', component: LoginPage, meta: { public: true } },
  { path: '/password-setup', component: PasswordSetupPage, meta: { public: true, titleKey: 'auth.passwordSetup.title' } },
  { path: '/systems', component: WorkspaceSelection },
  ...(['monitor', 'operations', 'configuration'] as const).map(system => ({
    path: '/' + system, component: system === 'monitor' ? MonitorLayout : OfficeLayout,
    meta: { system, mode: system === 'monitor' ? 'monitor' : 'office' },
    children: [
      { path: '', redirect: '/systems' },
      ...pages.filter(page => page.system === system).map(page => ({
        path: page.path, component: screens.find(screen => screen.path === page.path)?.load ?? businessRoute(page)?.component ?? PendingPage,
        props: () => ({ title: authorizedPages(useSession().menus).find(item => item.id === page.id)?.title ?? t(page.titleKey), ...(system === 'monitor' ? {} : { panel: true }) }),
        meta: { system, titleKey: page.titleKey, requiresPlatformAdmin: businessRoute(page)?.meta?.requiresPlatformAdmin, screenLayout: screens.find(screen => screen.path === page.path)?.layout ?? 'grid' },
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
    if (to.meta.requiresPlatformAdmin && !session.user.roles.includes('PLATFORM_ADMIN')) return '/403'
    if (to.meta.system && !authorizedPages(session.menus).some(page => page.path === to.path)) return '/403'
    return true
  })
  return router
}
