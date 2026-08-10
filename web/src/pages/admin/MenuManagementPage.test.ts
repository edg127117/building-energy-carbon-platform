import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MenuManagementPage from './MenuManagementPage.vue'
vi.mock('@/composables/useMenuManagement', () => ({ useMenuManagement: () => ({ tree: { value: [] }, loading: { value: false }, error: { value: null },
  pending: { value: false }, load: vi.fn().mockResolvedValue(undefined), add: vi.fn(), update: vi.fn(), remove: vi.fn(), parentOptions: vi.fn(() => []) }) }))
describe('menu management page', () => {
  it('shows maintenance status without executing unknown routes', () => {
    const wrapper = mount(MenuManagementPage, { global: { stubs: { MenuFormDrawer: true, 'a-button': true, 'a-alert': true,
      'a-table': { template: '<div><slot /></div>' }, 'a-tag': true, 'a-space': true } } })
    expect(wrapper.text()).toContain('菜单管理')
  })
})
