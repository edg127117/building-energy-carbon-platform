import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { Modal } from 'ant-design-vue'
import UserManagementPage from './UserManagementPage.vue'

vi.mock('@/composables/useUserManagement', () => ({ useUserManagement: () => ({
  page: { value: { records: [{
    id: 1, username: 'admin', nickname: '管理员', phone: null, status: 1, delFlag: 0,
    roles: ['PLATFORM_ADMIN', 'ENERGY_MANAGER'], buildingIds: [],
  }], total: 1, current: 1, size: 20 } }, query: { value: { page: 1, size: 20, includeDeleted: false } },
  loading: { value: false }, error: { value: null }, pending: { value: new Set() }, loadUsers: vi.fn().mockResolvedValue(undefined),
  createUser: vi.fn(), updateUser: vi.fn(), replaceUserRoles: vi.fn(), replaceUserBuildings: vi.fn(),
  resetUserPassword: vi.fn(), enableUser: vi.fn(), disableUser: vi.fn(), deleteUser: vi.fn(), restoreUser: vi.fn(),
}) }))

describe('user management page', () => {
  it('shows the lifecycle surface without fabricated rows', () => {
    const wrapper = mount(UserManagementPage, { global: { stubs: {
      UserEditorDrawer: true, UserRoleAssignmentDrawer: true, UserBuildingAssignmentDrawer: true,
      'a-table': { props: ['dataSource'], template: '<div data-test="table"><slot name="bodyCell" :column="{ key: \'roles\' }" :record="dataSource[0]" /><slot name="bodyCell" :column="{ key: \'actions\' }" :record="dataSource[0]" /></div>' },
      'a-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' }, 'a-input': true,
      'a-select': true, 'a-switch': true, 'a-alert': true,
      'a-tag': { template: '<span><slot /></span>' }, 'a-space': { template: '<div><slot /></div>' } } } })
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('平台管理员')
    expect(wrapper.text()).toContain('能效管理方')
    expect(wrapper.text()).not.toContain('PLATFORM_ADMIN')
    expect(wrapper.find('[data-test="table"]').exists()).toBe(true)
  })

  it('uses Chinese action labels in user confirmation dialogs', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(vi.fn())
    const wrapper = mount(UserManagementPage, { global: { stubs: {
      UserEditorDrawer: true, UserRoleAssignmentDrawer: true, UserBuildingAssignmentDrawer: true,
      'a-table': { props: ['dataSource'], template: '<div><slot name="bodyCell" :column="{ key: \'actions\' }" :record="dataSource[0]" /></div>' },
      'a-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' }, 'a-input': true,
      'a-select': true, 'a-switch': true, 'a-alert': true, 'a-tag': true, 'a-space': { template: '<div><slot /></div>' },
    } } })

    await wrapper.findAll('button').find((button) => button.text() === '停用')?.trigger('click')

    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ okText: '确认停用', cancelText: '取消' }))
  })
})
