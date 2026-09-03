import type { RouteRecordRaw } from 'vue-router'
export const routes: RouteRecordRaw[] = [{
  path: 'dashboard', name: 'dashboard', component: () => import('./pages/IndexPage.vue'),
  meta: { titleKey: 'dashboard.title', mode: 'office' },
}]
