import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import LoginPage from '@/pages/LoginPage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'

/**
 * HVAC V1 前端路由：登录和 403 页面公开，大屏需要本地 Token。
 * 路由表不决定接口权限，后端仍会校验 JWT 正式角色和建筑数据范围。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/login',
  },
  {
    path: '/login',
    name: 'login',
    component: LoginPage,
    meta: { public: true },
  },
  {
    path: '/hvac-demo',
    name: 'hvac-demo',
    component: () => import('@/pages/HvacDemoPage.vue'),
  },
  {
    path: '/system',
    component: () => import('@/layouts/ManagementLayout.vue'),
    meta: { requiresAuth: true, admin: true },
    children: [
      { path: 'users', name: 'system-users', component: () => import('@/pages/admin/UserManagementPage.vue') },
      { path: 'roles', name: 'system-roles', component: () => import('@/pages/admin/RoleManagementPage.vue') },
      { path: 'menus', name: 'system-menus', component: () => import('@/pages/admin/MenuManagementPage.vue') },
      { path: 'building-access', name: 'system-building-access', component: () => import('@/pages/admin/BuildingAccessManagementPage.vue') },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: ForbiddenPage,
    meta: { public: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 在导航前恢复本地认证并处理登录重定向与可选的管理员页面标记。
 *
 * 守卫只检查浏览器状态，不验证 Token 是否仍有效；过期、伪造或越权请求最终由
 * 后端返回 401/403，再由统一请求拦截器清理认证状态。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.token) auth.hydrateFromStorage()

  if (to.meta.public) return true

  if (!auth.token) return { path: '/login', query: { redirect: to.fullPath } }

  if (to.meta.admin && !auth.isAdmin) return { path: '/403' }

  return true
})

export default router
