import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import LoginPage from '@/pages/LoginPage.vue'
import DashboardPage from '@/pages/DashboardPage.vue'
import DevicePage from '@/pages/DevicePage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard',
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
    meta: { public: true },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: DashboardPage,
  },
  {
    path: '/device',
    name: 'device',
    component: DevicePage,
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

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.token) auth.hydrateFromStorage()

  if (to.meta.public) return true

  if (!auth.token) return { path: '/login', query: { redirect: to.fullPath } }

  if (to.meta.admin && !auth.isAdmin) return { path: '/403' }

  return true
})

export default router
