import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RoleManagementPage from './RoleManagementPage.vue'

const state = vi.hoisted(() => ({
  roles: [{ id: 3, roleKey: 'THIRD_PARTY' as const, roleName: '对方开发', dataScope: 'ALL', status: 1 }],
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
    roles: ref(state.roles),
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

const stubs = {
  RoleMenuTree: true,
  RouterLink: { template: '<a><slot /></a>' },
  'a-button': true,
  'a-alert': true,
  'a-empty': true,
}

describe('role management page', () => {
  beforeEach(() => { state.selectedRole = null })

  it('starts with a compact role list and no expanded detail', () => {
    const wrapper = mount(RoleManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('角色权限')
    expect(wrapper.text()).toContain('接口调用方')
    expect(wrapper.text()).not.toContain('新建角色')
    expect(wrapper.text()).not.toContain('菜单授权')
    expect(wrapper.text()).not.toContain('账号状态')
  })

  it('shows only the real management actions for the interface caller role', () => {
    state.selectedRole = { id: 3, roleKey: 'THIRD_PARTY', roleName: '对方开发', dataScope: 'ALL', status: 1 }
    const wrapper = mount(RoleManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('接口调用方')
    expect(wrapper.text()).toContain('按建筑授权 · 启用')
    expect(wrapper.text()).toContain('启用或停用账号')
    expect(wrapper.text()).toContain('分配账号可以读取的建筑')
    expect(wrapper.text()).toContain('管理接口账号')
    expect(wrapper.text()).toContain('目前不能在这里配置')
    expect(wrapper.text()).not.toContain('保存菜单授权')
    expect(wrapper.text()).not.toContain('JWT')
    expect(wrapper.findComponent({ name: 'RoleMenuTree' }).exists()).toBe(false)
  })
})
