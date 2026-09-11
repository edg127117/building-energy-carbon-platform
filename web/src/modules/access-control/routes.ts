import type { RouteRecordRaw } from 'vue-router'
import type { MenuRouteRegistration } from './models/access-control'

export const accessControlMenuRoutes: readonly MenuRouteRegistration[] = [
  { path: '/system/users', routeName: 'system-users' },
  { path: '/system/roles', routeName: 'system-roles' },
  { path: '/system/menus', routeName: 'system-menus' },
  { path: '/system/building-access', routeName: 'system-building-access' },
]

/** 继承后台菜单的地址作为兼容地址，实际组件均由新办公端外壳承载。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/system/users',
    name: 'system-users',
    component: () => import('./pages/UserManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.user.title' },
  },
  {
    path: '/system/roles',
    name: 'system-roles',
    component: () => import('./pages/RoleManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.role.title' },
  },
  {
    path: '/system/menus',
    name: 'system-menus',
    component: () => import('./pages/MenuManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.menu.title' },
  },
  {
    path: '/system/building-access',
    name: 'system-building-access',
    component: () => import('./pages/BuildingAccessManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.buildingAccess.title' },
  },
]
