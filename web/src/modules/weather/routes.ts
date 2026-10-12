import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [
  { path: '/operations/weather/overview', component: () => import('./pages/OverviewPage.vue') },
  { path: '/operations/weather/history', component: () => import('./pages/HistoryPage.vue') },
  { path: '/configuration/ingestion/weather', component: () => import('./pages/IngestionPage.vue'), meta: { requiresRelationManager: true } },
]
