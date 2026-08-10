import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminMenuTree, getRoleMenuIds, listRoles } from '@/api/systemAdmin'
import { normalizeCheckedMenuIds, useRoleManagement } from './useRoleManagement'

vi.mock('@/api/systemAdmin', () => ({
  getAdminMenuTree: vi.fn(),
  getRoleMenuIds: vi.fn(),
  listRoles: vi.fn(),
  replaceRoleMenus: vi.fn(),
}))
vi.mock('@/store/menu', () => ({ useMenuStore: () => ({ reload: vi.fn() }) }))

const tree = [{ id: 1, parentId: 0, menuName: '根', menuType: 'M' as const, path: null, component: null, perms: null,
  icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [
    { id: 2, parentId: 1, menuName: '叶', menuType: 'C' as const, path: '/hvac-demo', component: null, perms: null,
      icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [] },
  ] }]

describe('fixed role menu normalization', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('loads the role list without expanding a role by default', async () => {
    vi.mocked(listRoles).mockResolvedValue([{ id: 3, roleKey: 'THIRD_PARTY', roleName: '接口调用方', dataScope: 'BUILDING', status: 1 }])
    vi.mocked(getAdminMenuTree).mockResolvedValue([])
    const roles = useRoleManagement()

    await roles.load()

    expect(roles.selectedRole.value).toBeNull()
    expect(getRoleMenuIds).not.toHaveBeenCalled()
  })

  it('adds descendants and required ancestors to a unique sorted set', () => {
    expect(normalizeCheckedMenuIds(tree, [1])).toEqual([1, 2])
    expect(normalizeCheckedMenuIds(tree, [2, 2])).toEqual([1, 2])
  })
})
