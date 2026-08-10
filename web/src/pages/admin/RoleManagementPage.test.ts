import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleManagementPage from './RoleManagementPage.vue'
vi.mock('@/composables/useRoleManagement', () => ({ useRoleManagement: () => ({ roles: { value: [] }, tree: { value: [] },
  selectedRole: { value: null }, checkedIds: { value: [] }, loading: { value: false }, saving: { value: false }, error: { value: null },
  load: vi.fn().mockResolvedValue(undefined), selectRole: vi.fn(), save: vi.fn() }) }))
describe('role management page', () => {
  it('is read-only role metadata with no role CRUD', () => {
    const wrapper = mount(RoleManagementPage, { global: { stubs: { RoleMenuTree: true, 'a-button': true, 'a-alert': true, 'a-empty': true, 'a-tag': true } } })
    expect(wrapper.text()).toContain('角色权限')
    expect(wrapper.text()).not.toContain('新建角色')
  })
})
