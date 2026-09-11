import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/configuration/space/equipmentSpaces',
    name: 'equipment-space-associations',
    component: () => import('./pages/EquipmentSpacePage.vue'),
    meta: { titleKey: 'assetManagement.associations.title', mode: 'office', requiresRelationManager: true },
  },
  {
    path: '/system/buildings',
    name: 'asset-archive',
    component: () => import('./pages/AssetArchivePage.vue'),
    meta: { titleKey: 'assetManagement.archive.title', mode: 'office', requiresPlatformAdmin: true },
  },
  {
    path: '/system/devices',
    name: 'equipment-points',
    component: () => import('./pages/EquipmentPointPage.vue'),
    meta: { titleKey: 'assetManagement.equipment.title', mode: 'office', requiresPlatformAdmin: true },
  },
]
