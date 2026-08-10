import { ref } from 'vue'
import { addMenu, deleteMenu, getAdminMenuTree, updateMenu } from '@/api/systemAdmin'
import { useMenuStore } from '@/store/menu'
import type { MenuCreateRequest, MenuNode, MenuUpdateRequest } from '@/types/admin'

/** 完整维护树保留隐藏/停用节点；任何写入成功后同时刷新管理员树和当前受控导航。 */
export function useMenuManagement() {
  const tree = ref<MenuNode[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const pending = ref(false)
  let generation = 0
  const currentMenu = useMenuStore()
  const load = async () => {
    const owner = ++generation; loading.value = true; error.value = null
    try { const next = await getAdminMenuTree(); if (owner === generation) tree.value = next }
    catch (reason) { if (owner === generation) error.value = messageOf(reason); throw reason }
    finally { if (owner === generation) loading.value = false }
  }
  const command = async (action: () => Promise<unknown>) => {
    if (pending.value) return
    pending.value = true; error.value = null
    try { await action(); await Promise.all([load(), currentMenu.reload().catch(() => undefined)]) }
    catch (reason) { error.value = messageOf(reason); throw reason }
    finally { pending.value = false }
  }
  const add = (request: MenuCreateRequest) => command(() => addMenu(request))
  const update = (request: MenuUpdateRequest) => command(() => updateMenu(request))
  const remove = (id: number) => command(() => deleteMenu(id))
  return { tree, loading, error, pending, load, add, update, remove, parentOptions: (id?: number) => menuParentOptions(tree.value, id) }
}

/** 父级候选排除当前节点和全部后代；后端仍会复核父级存在、自引用和循环。 */
export function menuParentOptions(tree: MenuNode[], editedId?: number) {
  const excluded = new Set<number>()
  const find = (items: MenuNode[]): MenuNode | undefined => items.flatMap((item) => [item, ...flatten(item.children)]).find((item) => item.id === editedId)
  const edited = editedId === undefined ? undefined : find(tree)
  if (edited) flatten([edited]).forEach((item) => excluded.add(item.id))
  const options = [{ label: '顶级菜单', value: 0 }]
  const walk = (items: MenuNode[], depth: number) => items.forEach((item) => {
    if (!excluded.has(item.id)) options.push({ label: `${'　'.repeat(depth)}${item.menuName}`, value: item.id })
    walk(item.children, depth + 1)
  })
  walk(tree, 0)
  return options
}
function flatten(items: MenuNode[]): MenuNode[] { return items.flatMap((item) => [item, ...flatten(item.children)]) }
function messageOf(reason: unknown) { return reason instanceof Error ? reason.message : '菜单维护失败' }
