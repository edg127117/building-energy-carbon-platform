import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/configuration/space/equipmentSpaces',
    name: 'equipment-space-associations',
    component: () => import('./pages/EquipmentSpacePage.vue'),
    meta: { titleKey: 'assetManagement.associations.title', mode: 'office', requiresRelationManager: true },
  },
  {
    path: '/configuration/space/buildings',
    name: 'asset-archive',
    component: () => import('./pages/AssetArchivePage.vue'),
    meta: { titleKey: 'assetManagement.archive.title', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/operations/devices/businessDevices',
    name: 'equipment-points',
    component: () => import('./pages/EquipmentPointPage.vue'),
    meta: { titleKey: 'assetManagement.equipment.businessTitle', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/operations/devices/meters',
    name: 'meter-equipment-points',
    component: () => import('./pages/EquipmentPointPage.vue'),
    meta: { titleKey: 'assetManagement.equipment.meterTitle', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/operations/realtime/power',
    name: 'power-monitoring',
    component: () => import('./pages/PowerMonitoringPage.vue'),
    meta: { titleKey: 'assetManagement.powerMonitoring.title', mode: 'office' },
  },
]
