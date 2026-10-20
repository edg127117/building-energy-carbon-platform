import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/operations/energy/energyItems', component: () => import('./pages/EnergyClassificationPage.vue') },
  { path: '/operations/energy/energyZones', component: () => import('./pages/EnergyClassificationPage.vue') },
]
