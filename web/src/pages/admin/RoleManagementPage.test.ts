import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RoleManagementPage from './RoleManagementPage.vue'

const state = vi.hoisted(() => ({
  selectedRole: null as null | {
    id: number
    roleKey: 'THIRD_PARTY'
    roleName: string
    dataScope: string
    status: number
  },
}))

vi.mock('@/composables/useRoleManagement', () => ({
  useRoleManagement: () => ({
    roles: ref(state.selectedRole ? [state.selectedRole] : []),
    tree: ref([]),
    selectedRole: ref(state.selectedRole),
    checkedIds: ref([]),
    loading: ref(false),
    saving: ref(false),
    error: ref(null),
    load: vi.fn().mockResolvedValue(undefined),
    selectRole: vi.fn(),
    save: vi.fn(),
  }),
}))

const stubs = { RoleMenuTree: true, 'a-button': true, 'a-alert': true, 'a-empty': true }

describe('role management page', () => {
  beforeEach(() => { state.selectedRole = null })

  it('is read-only role metadata with no role CRUD', () => {
    const wrapper = mount(RoleManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('角色权限')
    expect(wrapper.text()).not.toContain('新建角色')
  })

  it('shows third-party API capability instead of an empty internal menu tree', () => {
    state.selectedRole = { id: 3, roleKey: 'THIRD_PARTY', roleName: '对方开发', dataScope: 'ALL', status: 1 }
    const wrapper = mount(RoleManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('API 接入权限')
    expect(wrapper.text()).toContain('按建筑授权 · 启用')
    expect(wrapper.text()).toContain('仅限授权建筑')
    expect(wrapper.text()).toContain('实时 HVAC 数据')
    expect(wrapper.text()).not.toContain('保存菜单授权')
    expect(wrapper.findComponent({ name: 'RoleMenuTree' }).exists()).toBe(false)
  })
})
