import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/configuration/rules/baselines', component: () => import('./pages/CarbonPlanPage.vue') },
  { path: '/operations/energy/energyZones', component: () => import('./pages/EnergyClassificationPage.vue') },
]
