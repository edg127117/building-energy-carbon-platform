export { default as messages } from './locales/zh-CN'
export { routes, accessControlMenuRoutes } from './routes'
export { useSensitiveChange } from './composables/use-sensitive-change'
export { default as ChangeRequestControl } from './components/ChangeRequestControl.vue'
export { newIdempotencyKey } from './api/access-control'
export type {
  CurrentMenuItem,
  MenuNode,
  MenuRouteRegistration,
  SensitiveChange,
  SensitiveChangeOperation,
  UserView,
} from './models/access-control'
