import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [{
  path: 'dashboard', name: 'dashboard', component: () => import('./pages/HvacMonitoringPage.vue'),
  meta: { titleKey: 'dashboard.title', mode: 'office' },
}, {
  path: '/operations/alarms/liveAlarms', component: () => import('./pages/DaikinAlarmsPage.vue'),
}, {
  path: '/operations/alarms/historyAlarms', component: () => import('./pages/DaikinAlarmsPage.vue'),
}]
