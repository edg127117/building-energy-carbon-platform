import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('./pages/LoginPage.vue'),
    meta: { public: true, titleKey: 'auth.login.title' },
  },
  {
    path: '/password-setup',
    name: 'password-setup',
    component: () => import('./pages/PasswordSetupPage.vue'),
    meta: { public: true, titleKey: 'auth.passwordSetup.title' },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('./pages/ForbiddenPage.vue'),
    meta: { public: true, titleKey: 'auth.forbidden.title' },
  },
]
