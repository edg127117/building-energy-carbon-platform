import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/operations/alarms/dataQuality', component: () => import('./pages/TelemetryConflictPage.vue') },
]
