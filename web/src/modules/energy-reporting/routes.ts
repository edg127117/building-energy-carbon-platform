import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/operations/energy/energyItems', component: () => import('./pages/EnergyItemsEntryPage.vue') },
]
