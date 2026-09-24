import type { RouteRecordRaw } from 'vue-router'
import type { MenuRouteRegistration } from './models/access-control'

export const accessControlMenuRoutes: readonly MenuRouteRegistration[] = [
  { path: '/configuration/access/users', routeName: 'system-users' },
  { path: '/configuration/access/roles', routeName: 'system-roles' },
  { path: '/configuration/settings/menus', routeName: 'system-menus' },
  { path: '/configuration/access/buildingAccess', routeName: 'system-building-access' },
  { path: '/configuration/access/changeRequests', routeName: 'system-change-requests' },
]

/** 页面仅注册规范工作区路径，后端 API 地址不受前端路由迁移影响。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/configuration/access/users',
    name: 'system-users',
    component: () => import('./pages/UserManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.user.title' },
  },
  {
    path: '/configuration/access/roles',
    name: 'system-roles',
    component: () => import('./pages/RoleManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.role.title' },
  },
  {
    path: '/configuration/settings/menus',
    name: 'system-menus',
    component: () => import('./pages/MenuManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.menu.title' },
  },
  {
    path: '/configuration/access/buildingAccess',
    name: 'system-building-access',
    component: () => import('./pages/BuildingAccessManagementPage.vue'),
    meta: { requiresPlatformAdmin: true, titleKey: 'accessControl.buildingAccess.title' },
  },
  {
    path: '/configuration/access/changeRequests',
    name: 'system-change-requests',
    component: () => import('./pages/SensitiveChangePage.vue'),
    meta: { titleKey: 'accessControl.change.title' },
  },
]
