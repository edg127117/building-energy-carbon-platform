import { ref } from 'vue'
import { getAdminMenuTree, getRoleMenuIds, listRoles, replaceRoleMenus } from '@/api/systemAdmin'
import { useMenuStore } from '@/store/menu'
import type { MenuNode, RoleAdminView } from '@/types/admin'

/** 固定角色只允许全量替换菜单 ID；异步代次避免切换角色时旧响应污染当前选择。 */
export function useRoleManagement() {
  const roles = ref<RoleAdminView[]>([])
  const tree = ref<MenuNode[]>([])
  const selectedRole = ref<RoleAdminView | null>(null)
  const checkedIds = ref<number[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)
  let generation = 0
  const menuStore = useMenuStore()

  const selectRole = async (role: RoleAdminView) => {
    const owner = ++generation
    selectedRole.value = role
    if (role.roleKey === 'THIRD_PARTY') {
      checkedIds.value = []
      loading.value = false
      error.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      const ids = await getRoleMenuIds(role.id)
      if (owner === generation) checkedIds.value = ids
    } catch (reason) {
      if (owner === generation) error.value = messageOf(reason)
    } finally {
      if (owner === generation) loading.value = false
    }
  }

  const load = async () => {
    loading.value = true
    error.value = null
    try {
      const [nextRoles, nextTree] = await Promise.all([listRoles(), getAdminMenuTree()])
      roles.value = nextRoles
      tree.value = nextTree
    } catch (reason) {
      error.value = messageOf(reason)
      loading.value = false
    }
  }

  const save = async () => {
    if (!selectedRole.value || selectedRole.value.roleKey === 'THIRD_PARTY' || saving.value) return
    saving.value = true
    error.value = null
    try {
      const normalized = normalizeCheckedMenuIds(tree.value, checkedIds.value)
      await replaceRoleMenus(selectedRole.value.id, normalized)
      checkedIds.value = normalized
      await menuStore.reload().catch(() => undefined)
    } catch (reason) {
      error.value = messageOf(reason)
      throw reason
    } finally {
      saving.value = false
    }
  }

  return { roles, tree, selectedRole, checkedIds, loading, saving, error, load, selectRole, save }
}

/** 选择父级包含全部后代，选择叶级补齐父链；结果是稳定、去重的显式授权集合。 */
export function normalizeCheckedMenuIds(tree: MenuNode[], checkedIds: number[]): number[] {
  const nodes = new Map<number, MenuNode>()
  const parent = new Map<number, number>()
  const visit = (items: MenuNode[]) => items.forEach((item) => {
    nodes.set(item.id, item)
    if (item.parentId) parent.set(item.id, item.parentId)
    visit(item.children)
  })
  visit(tree)
  const result = new Set<number>()
  const addDescendants = (id: number) => {
    const node = nodes.get(id)
    if (!node || result.has(id)) return
    result.add(id)
    node.children.forEach((child) => addDescendants(child.id))
  }
  checkedIds.forEach(addDescendants)
  Array.from(result).forEach((id) => {
    let current = parent.get(id)
    while (current) { result.add(current); current = parent.get(current) }
  })
  return Array.from(result).sort((a, b) => a - b)
}

function messageOf(reason: unknown) { return reason instanceof Error ? reason.message : '角色菜单加载失败' }
