import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentMenu } from '@/api/systemAdmin'
import {
  buildImplementedNavigation,
  type AdminNavigationItem,
} from '@/domain/adminNavigation'
import type { MenuNode } from '@/types/admin'

/**
 * 保存后端 `/menu/current` 的最近成功结果及其受控导航映射。
 * generation 隔离刷新、退出和旧请求回调；失败保留最后成功树，便于页面明确重试而不闪空。
 */
export const useMenuStore = defineStore('menu', () => {
  const tree = ref<MenuNode[]>([])
  const navigation = ref<AdminNavigationItem[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)
  const generation = ref(0)
  let inFlight: Promise<void> | null = null

  const runLoad = (force: boolean): Promise<void> => {
    if (!force && loaded.value) return Promise.resolve()
    if (!force && inFlight) return inFlight

    const owner = ++generation.value
    loading.value = true
    error.value = null
    const request = getCurrentMenu()
      .then((nextTree) => {
        if (owner !== generation.value) return
        tree.value = nextTree
        navigation.value = buildImplementedNavigation(nextTree)
        loaded.value = true
      })
      .catch((reason: unknown) => {
        if (owner === generation.value) {
          error.value = reason instanceof Error ? reason.message : '菜单加载失败'
        }
        throw reason
      })
      .finally(() => {
        if (owner === generation.value) loading.value = false
        if (inFlight === request) inFlight = null
      })
    inFlight = request
    return request
  }

  const ensureLoaded = () => runLoad(false)
  const reload = () => runLoad(true)

  /** 退出或身份切换时立即撤销旧请求所有权，迟到结果不能恢复前一账号的菜单。 */
  const clear = () => {
    generation.value += 1
    inFlight = null
    tree.value = []
    navigation.value = []
    loading.value = false
    loaded.value = false
    error.value = null
  }

  return { tree, navigation, loading, loaded, error, generation, ensureLoaded, reload, clear }
})
