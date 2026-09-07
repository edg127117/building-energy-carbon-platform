import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from '@/modules/auth/public'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { getCurrentMenu } from '../api/access-control'
import type { CurrentMenuItem, MenuNode, MenuRouteRegistration } from '../models/access-control'
import { accessControlMenuRoutes } from '../routes'

/** 数据库菜单只控制可见导航；可访问组件仍由前端静态路由注册表限定。 */
export function buildCurrentMenuNavigation(
  tree: MenuNode[],
  registrations: readonly MenuRouteRegistration[] = accessControlMenuRoutes,
): CurrentMenuItem[] {
  const registrationsByPath = new Map(registrations.map(item => [item.path, item]))
  const emitted = new Set<string>()

  const visit = (node: MenuNode): CurrentMenuItem | null => {
    if (node.visible !== 1 || node.status !== 1) return null
    const children = node.children.map(visit).filter((item): item is CurrentMenuItem => item !== null)
    const registration = node.path ? registrationsByPath.get(node.path) : undefined
    if (node.menuType === 'C' && registration && !emitted.has(registration.path)) {
      emitted.add(registration.path)
      return { id: node.id, label: node.menuName, path: registration.path, routeName: registration.routeName, children }
    }
    return children.length > 0 ? { id: node.id, label: node.menuName, children } : null
  }

  return tree.map(visit).filter((item): item is CurrentMenuItem => item !== null)
}

/** 退出、身份切换和迟到请求使用同一代次边界，避免旧菜单重新写回新会话。 */
export const useCurrentMenuStore = defineStore('access-current-menu', () => {
  const auth = useAuthStore()
  const tree = ref<MenuNode[]>([])
  const navigation = computed(() => buildCurrentMenuNavigation(tree.value))
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)
  let generation = 0
  let inFlight: Promise<void> | null = null

  function load(force = false): Promise<void> {
    if (!force && loaded.value) return Promise.resolve()
    if (!force && inFlight) return inFlight
    const owner = ++generation
    loading.value = true
    error.value = null
    const request = getCurrentMenu()
      .then(next => {
        if (owner !== generation) return
        tree.value = next
        loaded.value = true
      })
      .catch(reason => {
        if (owner === generation) error.value = requestErrorMessage(reason)
        throw reason
      })
      .finally(() => {
        if (owner === generation) loading.value = false
        if (inFlight === request) inFlight = null
      })
    inFlight = request
    return request
  }

  function clear() {
    generation += 1
    inFlight = null
    tree.value = []
    loading.value = false
    loaded.value = false
    error.value = null
  }

  // 身份令牌变更即丢弃旧导航，避免同一浏览器切换账号时沿用前一账号的菜单。
  watch(() => auth.token, (token, previousToken) => {
    if (token !== previousToken) clear()
  })

  return { tree, navigation, loading, loaded, error, ensureLoaded: () => load(false), reload: () => load(true), clear }
})
