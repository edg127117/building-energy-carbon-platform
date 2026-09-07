import { computed, ref } from 'vue'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { getAdminMenuTree } from '../api/access-control'
import type { MenuCommand, MenuNode, MenuUpdateCommand } from '../models/access-control'
import { useSensitiveChange } from './use-sensitive-change'

export type MenuParentOption = { label: string; value: number }

/** 管理树保留隐藏和停用节点；写操作发起申请后不提前把本地树伪装成已生效结果。 */
export function useMenuManagement() {
  const tree = ref<MenuNode[]>([])
  const loading = ref(false)
  const localError = ref<string | null>(null)
  const expandedMenuIds = ref<number[]>([])
  const changes = useSensitiveChange()
  let generation = 0

  async function load() {
    const owner = ++generation
    loading.value = true
    localError.value = null
    try {
      const next = await getAdminMenuTree()
      if (owner === generation) {
        tree.value = next
        expandedMenuIds.value = reconcileExpandedMenuIds(expandedMenuIds.value, next)
      }
    } catch (reason) {
      if (owner === generation) localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  function requestCreate(command: MenuCommand) {
    return changes.start('CREATE_MENU', command, `menu-create:${command.menuName}`)
  }

  function requestUpdate(command: MenuUpdateCommand) {
    return changes.start('UPDATE_MENU', command, `menu-update:${command.id}`)
  }

  function requestDelete(menuId: number) {
    return changes.start('DELETE_MENU', { menuId }, `menu-delete:${menuId}`)
  }

  return {
    tree,
    loading,
    error: computed(() => localError.value ?? changes.error.value),
    expandedMenuIds,
    load,
    requestCreate,
    requestUpdate,
    requestDelete,
    parentOptions: (editedId?: number) => menuParentOptions(tree.value, editedId),
    changes,
  }
}

export function reconcileExpandedMenuIds(current: number[], tree: MenuNode[]): number[] {
  const expandable = new Set<number>()
  const visit = (items: MenuNode[]) => items.forEach(item => {
    if (item.children.length > 0) expandable.add(item.id)
    visit(item.children)
  })
  visit(tree)
  return current.filter(id => expandable.has(id))
}

export function menuParentOptions(tree: MenuNode[], editedId?: number): MenuParentOption[] {
  const excluded = new Set<number>()
  const flatten = (items: MenuNode[]): MenuNode[] => items.flatMap(item => [item, ...flatten(item.children)])
  const edited = editedId === undefined ? undefined : flatten(tree).find(item => item.id === editedId)
  if (edited) flatten([edited]).forEach(item => excluded.add(item.id))
  const options: MenuParentOption[] = [{ label: t('accessControl.menu.root'), value: 0 }]
  const visit = (items: MenuNode[], depth: number) => items.forEach(item => {
    if (!excluded.has(item.id) && item.menuType !== 'F') {
      options.push({ label: `${'　'.repeat(depth)}${item.menuName}`, value: item.id })
    }
    visit(item.children, depth + 1)
  })
  visit(tree, 0)
  return options
}
