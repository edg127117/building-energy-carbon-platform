import { describe, expect, it, vi, beforeEach } from 'vitest'
import { pageUsers, updateUserStatus } from '@/api/systemAdmin'
import { useUserManagement } from './useUserManagement'

vi.mock('@/api/systemAdmin', () => ({
  pageUsers: vi.fn(), createUser: vi.fn(), updateUser: vi.fn(), replaceUserRoles: vi.fn(),
  replaceUserBuildings: vi.fn(), resetUserPassword: vi.fn(), updateUserStatus: vi.fn(),
  deleteUser: vi.fn(), restoreUser: vi.fn(),
}))

const emptyPage = { records: [], total: 0, current: 1, size: 20 }

describe('user management ownership', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads the configured first page', async () => {
    vi.mocked(pageUsers).mockResolvedValue(emptyPage)
    const users = useUserManagement()
    await users.loadUsers()
    expect(pageUsers).toHaveBeenCalledWith({ page: 1, size: 20, includeDeleted: false })
  })

  it('refreshes after a state command and blocks duplicates', async () => {
    vi.mocked(pageUsers).mockResolvedValue(emptyPage)
    vi.mocked(updateUserStatus).mockResolvedValue()
    const users = useUserManagement()
    await Promise.all([users.disableUser(7), users.disableUser(7)])
    expect(updateUserStatus).toHaveBeenCalledTimes(1)
    expect(pageUsers).toHaveBeenCalledTimes(1)
  })
})
