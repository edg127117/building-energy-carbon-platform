import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import LoginPage from '@/pages/LoginPage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'

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
