import { createRouter, createWebHashHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import OfficeLayout from '@/app/layouts/office/OfficeLayout.vue'
import MonitorLayout from '@/app/layouts/monitor/MonitorLayout.vue'
import PendingPage from '@/shared/components/PendingPage.vue'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import { routes as dashboardRoutes } from '@/modules/dashboard/public'
import { routes as drilldownRoutes } from '@/modules/drilldown/public'
import { routes as trendRoutes } from '@/modules/trend-analysis/public'
import { routes as screenRoutes } from '@/modules/large-screen/public'
import { t } from '@/locales'

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/office' },
  {
    path: '/office', component: OfficeLayout, meta: { mode: 'office' },
    children: [
      { path: '', component: PendingPage, props: () => ({ title: t('navigation.office') }), meta: { titleKey: 'navigation.office' } },
      ...dashboardRoutes, ...drilldownRoutes, ...trendRoutes,
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

/** 占位入口不请求受保护数据；权限契约接入后由应用层安装守卫，不能据空页面推断已鉴权。 */
export function createPlatformRouter(history: RouterHistory = createWebHashHistory()) {
  return createRouter({ history, routes })
}
