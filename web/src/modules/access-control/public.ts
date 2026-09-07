export { default as messages } from './locales/zh-CN'
export { routes, accessControlMenuRoutes } from './routes'
export { useCurrentMenuStore, buildCurrentMenuNavigation } from './stores/current-menu'
export { useSensitiveChange } from './composables/use-sensitive-change'
export type {
  CurrentMenuItem,
  MenuNode,
  MenuRouteRegistration,
  SensitiveChange,
  SensitiveChangeOperation,
  UserView,
} from './models/access-control'
