import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [{
  path: 'drilldown', name: 'drilldown', component: () => import('./pages/IndexPage.vue'),
  meta: { titleKey: 'drilldown.title', mode: 'office' },
}]
