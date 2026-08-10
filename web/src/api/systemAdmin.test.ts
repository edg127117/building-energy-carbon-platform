import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/utils/request'
import {
  addMenu,
  approveBuildingAccessRequest,
  createUser,
  deleteMenu,
  getAdminMenuTree,
  getCurrentMenu,
  getRoleMenuIds,
  getUserDetail,
  listBuildingAccessRequests,
  listRoles,
  pageBuildings,
  pageUsers,
  rejectBuildingAccessRequest,
  replaceRoleMenus,
  replaceUserBuildings,
  replaceUserRoles,
  resetUserPassword,
  restoreUser,
  updateMenu,
  updateUser,
  updateUserStatus,
  deleteUser,
} from './systemAdmin'

vi.mock('@/utils/request', () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

const response = { data: { data: { ok: true } } }

describe('system administration api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(http.get).mockResolvedValue(response)
    vi.mocked(http.post).mockResolvedValue(response)
    vi.mocked(http.put).mockResolvedValue(response)
    vi.mocked(http.delete).mockResolvedValue(response)
  })

  it('maps user lifecycle requests exactly', async () => {
    await pageUsers({ page: 2, size: 20, keyword: 'owner', status: 1, includeDeleted: false })
    expect(http.get).toHaveBeenCalledWith('/system/users', {
      params: { page: 2, size: 20, keyword: 'owner', status: 1, includeDeleted: false },
    })
    await getUserDetail(7)
    await createUser({ username: 'user', password: 'secret1', roleKeys: ['BUILDING_OWNER'], buildingIds: [] })
    await updateUser(7, { nickname: '业主', phone: null })
    await updateUserStatus(7, 0)
    await resetUserPassword(7, 'secret2')
    await replaceUserRoles(7, ['ENERGY_MANAGER'])
    await replaceUserBuildings(7, ['BLD001'])
    expect(http.put).toHaveBeenCalledWith('/system/users/7/buildings', { buildingIds: ['BLD001'] })
    await deleteUser(7)
    await restoreUser(7)
  })

  it('maps roles, menus, buildings and reviews exactly', async () => {
    await listRoles()
    await getRoleMenuIds(13)
    await replaceRoleMenus(13, [100, 101])
    await getAdminMenuTree()
    expect(http.get).toHaveBeenCalledWith('/menu/admin/tree')
    await getCurrentMenu()
    await addMenu({ parentId: 200, menuName: '测试', menuType: 'C', visible: 0, status: 1, sortOrder: 1 })
    await updateMenu({ id: 99, parentId: 200, menuName: '测试', menuType: 'C', visible: 0, status: 1, sortOrder: 1 })
    await deleteMenu(99)
    await pageBuildings({ page: 1, size: 100, keyword: '一号' })
    await listBuildingAccessRequests('PENDING')
    await approveBuildingAccessRequest(5, '通过')
    await rejectBuildingAccessRequest(6, undefined)
    expect(http.put).toHaveBeenCalledWith('/system/building-access/requests/5/approve', { comment: '通过' })
    expect(http.put).toHaveBeenCalledWith('/system/building-access/requests/6/reject', {})
  })
})
