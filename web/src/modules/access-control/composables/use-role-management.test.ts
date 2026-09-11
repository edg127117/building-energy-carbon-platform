import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminMenuTree, getRoleMenuIds, listRoles } from '../api/access-control'
import { normalizeCheckedMenuIds, useRoleManagement } from './use-role-management'

vi.mock('../api/access-control', () => ({
  getAdminMenuTree: vi.fn(),
  getRoleMenuIds: vi.fn(),
  listRoles: vi.fn(),
  createChangeRequest: vi.fn(),
  getChangeRequest: vi.fn(),
  newIdempotencyKey: vi.fn(),
  submitChangeRequest: vi.fn(),
  withdrawChangeRequest: vi.fn(),
  approveChangeRequest: vi.fn(),
  rejectChangeRequest: vi.fn(),
  executeChangeRequest: vi.fn(),
}))

const tree = [{
  id: 1, parentId: 0, menuName: 'root', menuType: 'M' as const, path: null, component: null, perms: null,
  icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [{
    id: 2, parentId: 1, menuName: 'leaf', menuType: 'C' as const, path: '/system/users', component: null,
    perms: null, icon: null, visible: 1 as const, status: 1 as const, sortOrder: 0, children: [],
  }],
}]

describe('fixed role menu authorization', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads role candidates without loading a menu assignment until a role is selected', async () => {
    vi.mocked(listRoles).mockResolvedValue([{ id: 3, roleKey: 'THIRD_PARTY', roleName: 'client', dataScope: 'BUILDING', status: 1 }])
    vi.mocked(getAdminMenuTree).mockResolvedValue([])
    const roles = useRoleManagement()

    await roles.load()

    expect(roles.selectedRole.value).toBeNull()
    expect(getRoleMenuIds).not.toHaveBeenCalled()
  })

  it('adds required descendants and ancestors into a unique ordered selection', () => {
    expect(normalizeCheckedMenuIds(tree, [1])).toEqual([1, 2])
    expect(normalizeCheckedMenuIds(tree, [2, 2])).toEqual([1, 2])
  })
})
