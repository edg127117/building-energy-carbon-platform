import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [{
  path: '/system/protocol-configurations',
  name: 'protocol-configurations',
  component: () => import('./pages/ProtocolConfigurationPage.vue'),
  meta: { titleKey: 'protocolConfiguration.title', mode: 'office', requiresPlatformAdmin: true },
}]
