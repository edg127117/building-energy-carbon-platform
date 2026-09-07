import { createRouter, createWebHashHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import OfficeLayout from '@/app/layouts/office/OfficeLayout.vue'
import MonitorLayout from '@/app/layouts/monitor/MonitorLayout.vue'
import PendingPage from '@/shared/components/PendingPage.vue'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import { routes as dashboardRoutes } from '@/modules/dashboard/public'
import { routes as drilldownRoutes } from '@/modules/drilldown/public'
import { routes as trendRoutes } from '@/modules/trend-analysis/public'
import { routes as screenRoutes } from '@/modules/large-screen/public'
import { routes as authRoutes } from '@/modules/auth/public'
import { routes as accessControlRoutes } from '@/modules/access-control/public'
import { routes as assetRoutes } from '@/modules/asset-management/public'
import { routes as deviceRoutes } from '@/modules/device-onboarding/public'
import { t } from '@/locales'

export const routes: RouteRecordRaw[] = [
  ...authRoutes,
  { path: '/', redirect: '/office/dashboard' },
  {
    path: '/office', component: OfficeLayout, meta: { mode: 'office' },
    children: [
      { path: '', redirect: '/office/dashboard' },
      ...dashboardRoutes, ...drilldownRoutes, ...trendRoutes,
      ...accessControlRoutes, ...assetRoutes, ...deviceRoutes,
    ],
  },
  {
    path: '/monitor', component: MonitorLayout, meta: { mode: 'monitor' },
    children: [
      { path: '', component: PendingPage, props: () => ({ title: t('navigation.switchScreen') }), meta: { titleKey: 'navigation.switchScreen' } },
      ...screenRoutes,
      { path: ':pathMatch(.*)*', component: StateBoundary, props: { state: 'not-found' } },
    ],
  },
  { path: '/:pathMatch(.*)*', component: OfficeLayout, children: [
    { path: '', component: StateBoundary, props: { state: 'not-found' } },
  ] },
]

/** 创建唯一前端路由实例；认证桥接由应用入口在 Pinia 安装后统一注册。 */
export function createPlatformRouter(history: RouterHistory = createWebHashHistory()) {
  return createRouter({ history, routes })
}
