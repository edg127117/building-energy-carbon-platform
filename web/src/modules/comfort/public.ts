import type { RouteRecordRaw } from 'vue-router'

export { default as messages } from './locales/zh-CN'

export const comfortRoute: RouteRecordRaw = {
  path: '/operations/realtime/comfort',
  component: () => import('./pages/ComfortPage.vue'),
}
