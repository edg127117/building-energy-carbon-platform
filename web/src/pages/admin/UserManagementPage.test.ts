import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserManagementPage from './UserManagementPage.vue'

vi.mock('@/composables/useUserManagement', () => ({ useUserManagement: () => ({
  page: { value: { records: [], total: 0, current: 1, size: 20 } }, query: { value: { page: 1, size: 20, includeDeleted: false } },
  loading: { value: false }, error: { value: null }, pending: { value: new Set() }, loadUsers: vi.fn().mockResolvedValue(undefined),
  createUser: vi.fn(), updateUser: vi.fn(), replaceUserRoles: vi.fn(), replaceUserBuildings: vi.fn(),
  resetUserPassword: vi.fn(), enableUser: vi.fn(), disableUser: vi.fn(), deleteUser: vi.fn(), restoreUser: vi.fn(),
}) }))

describe('user management page', () => {
  it('shows the lifecycle surface without fabricated rows', () => {
    const wrapper = mount(UserManagementPage, { global: { stubs: {
      UserEditorDrawer: true, UserRoleAssignmentDrawer: true, UserBuildingAssignmentDrawer: true,
      'a-table': { template: '<div data-test="table"><slot /></div>' }, 'a-button': true, 'a-input': true,
      'a-select': true, 'a-switch': true, 'a-alert': true, 'a-tag': true, 'a-space': true } } })
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.find('[data-test="table"]').exists()).toBe(true)
  })
})
