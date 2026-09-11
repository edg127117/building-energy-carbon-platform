import { computed, ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { getAdminMenuTree, getRoleMenuIds, listRoles } from '../api/access-control'
import type { MenuNode, RoleView } from '../models/access-control'
import { useSensitiveChange } from './use-sensitive-change'

/** 角色切换以代次隔离，避免迟到的菜单授权覆盖当前所选角色。 */
export function useRoleManagement() {
  const roles = ref<RoleView[]>([])
  const tree = ref<MenuNode[]>([])
  const selectedRole = ref<RoleView | null>(null)
  const checkedIds = ref<number[]>([])
  const loading = ref(false)
  const localError = ref<string | null>(null)
  const changes = useSensitiveChange()
  let selectionGeneration = 0

  async function load() {
    loading.value = true
    localError.value = null
    try {
      const [nextRoles, nextTree] = await Promise.all([listRoles(), getAdminMenuTree()])
      roles.value = nextRoles
      tree.value = nextTree
    } catch (reason) {
      localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      loading.value = false
    }
  }

  async function select(role: RoleView) {
    const owner = ++selectionGeneration
    selectedRole.value = role
    checkedIds.value = []
    if (role.roleKey === 'THIRD_PARTY') return
    loading.value = true
    localError.value = null
    try {
      const ids = await getRoleMenuIds(role.id)
      if (owner === selectionGeneration) checkedIds.value = ids
    } catch (reason) {
      if (owner === selectionGeneration) localError.value = requestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === selectionGeneration) loading.value = false
    }
  }

  function requestMenuAssignment() {
    const role = selectedRole.value
    if (!role || role.roleKey === 'THIRD_PARTY') return Promise.resolve(undefined)
    const menuIds = normalizeCheckedMenuIds(tree.value, checkedIds.value)
    return changes.start('REPLACE_ROLE_MENUS', { roleId: role.id, menuIds }, `role-menu:${role.id}`)
  }

  return {
    roles,
    tree,
    selectedRole,
    checkedIds,
    loading,
    error: computed(() => localError.value ?? changes.error.value),
    load,
    select,
    requestMenuAssignment,
    changes,
  }
}

/** 选择目录会补齐下级，选择叶子会补齐父级，形成稳定的显式菜单集合。 */
export function normalizeCheckedMenuIds(tree: MenuNode[], checkedIds: number[]): number[] {
  const nodes = new Map<number, MenuNode>()
  const parents = new Map<number, number>()
  const visit = (items: MenuNode[]) => {
    items.forEach(item => {
      nodes.set(item.id, item)
      if (item.parentId) parents.set(item.id, item.parentId)
      visit(item.children)
    })
  }
  visit(tree)

  const result = new Set<number>()
  const addWithChildren = (id: number) => {
    const node = nodes.get(id)
    if (!node || result.has(id)) return
    result.add(id)
    node.children.forEach(child => addWithChildren(child.id))
  }
  checkedIds.forEach(addWithChildren)
  Array.from(result).forEach(id => {
    let parent = parents.get(id)
    while (parent) {
      result.add(parent)
      parent = parents.get(parent)
    }
  })
  return Array.from(result).sort((left, right) => left - right)
}
