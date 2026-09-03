import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [{
  path: 'trends', name: 'trend-analysis', component: () => import('./pages/IndexPage.vue'),
  meta: { titleKey: 'trendAnalysis.title', mode: 'office' },
}]
