import type { ScreenRegistration } from '@/shared/models/screen-registration'

export const screens: readonly ScreenRegistration[] = [
  { id: 'screen-monitoring', titleKey: 'largeScreen.monitoring', path: '/monitor/monitoring', groupKey: null, order: null,
    load: () => import('../pages/monitoring/IndexPage.vue') },
  { id: 'screen-trend', titleKey: 'largeScreen.trend', path: '/monitor/trend', groupKey: null, order: null,
    load: () => import('../pages/trend/IndexPage.vue') },
  { id: 'screen-situation', titleKey: 'largeScreen.situation', path: '/monitor/situation', groupKey: null, order: null,
    load: () => import('../pages/situation/IndexPage.vue') },
  { id: 'screen-status', titleKey: 'largeScreen.status', path: '/monitor/status', groupKey: null, order: null,
    load: () => import('../pages/status/IndexPage.vue') },
  { id: 'screen-analysis', titleKey: 'largeScreen.analysis', path: '/monitor/analysis', groupKey: null, order: null,
    load: () => import('../pages/analysis/IndexPage.vue') },
]
