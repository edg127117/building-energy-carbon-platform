import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/system/device-products',
    name: 'device-products',
    component: () => import('./pages/ProductTemplatePage.vue'),
    meta: { titleKey: 'deviceOnboarding.products.title', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/system/device-onboarding',
    name: 'pending-devices',
    component: () => import('./pages/PendingDevicePage.vue'),
    meta: { titleKey: 'deviceOnboarding.pending.title', mode: 'office', requiresPlatformAdmin: true },
  },
]
