import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/configuration/ingestion/products',
    name: 'device-products',
    component: () => import('./pages/ProductTemplatePage.vue'),
    meta: { titleKey: 'deviceOnboarding.products.title', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/operations/devices/pendingDevices',
    name: 'operations-pending-devices',
    component: () => import('./pages/PendingDevicePage.vue'),
    meta: { titleKey: 'deviceOnboarding.pending.title', mode: 'office' },
  },
]
