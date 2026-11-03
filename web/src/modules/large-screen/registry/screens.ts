import type { ScreenRegistration } from '@/shared/models/screen-registration'

export const screens: readonly ScreenRegistration[] = [
  { id: 'screen-monitoring', titleKey: 'largeScreen.monitoring', path: '/monitor/monitoring', groupKey: null, order: null, layout: 'scene',
    load: () => import('../pages/monitoring/FinalBPage.vue') },
]

/** 仅用于忽略升级期间菜单缓存中的已退役叶子，不映射或授予新页面。 */
export const retiredScreenPaths: readonly string[] = ['/monitor/trend', '/monitor/situation', '/monitor/status', '/monitor/analysis']
