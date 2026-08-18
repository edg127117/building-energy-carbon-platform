import { describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import DeviceOnboardingPage from './DeviceOnboardingPage.vue'
import DeviceProductManagementPage from './DeviceProductManagementPage.vue'

const management = {
  productPage: { value: { page: 1, size: 20, total: 0, items: [] } }, productsLoading: { value: false }, productsError: { value: { message: '当前账号没有权限执行此操作', forbidden: true } },
  selectedProduct: { value: null }, productDetailLoading: { value: false }, productDetailError: { value: null },
  pendingPage: { value: { page: 1, size: 20, total: 0, items: [] } }, pendingLoading: { value: false }, pendingError: { value: { message: '当前账号没有权限执行此操作', forbidden: true } },
  selectedPending: { value: null }, pendingDetailLoading: { value: false }, pendingDetailError: { value: null }, bindResult: { value: null }, activationResult: { value: null }, running: { value: new Set<string>() },
  loadProducts: vi.fn().mockRejectedValue({ response: { status: 403 } }), setProductQuery: vi.fn(), selectProduct: vi.fn(), saveProduct: vi.fn(), copyProduct: vi.fn(), changeProductEnabled: vi.fn(),
  loadPending: vi.fn().mockRejectedValue({ response: { status: 403 } }), setPendingQuery: vi.fn(), selectPending: vi.fn(), changePendingStatus: vi.fn(), bind: vi.fn(), activate: vi.fn(),
}

vi.mock('@/composables/useDeviceOnboardingManagement', () => ({ useDeviceOnboardingManagement: () => management }))
vi.mock('@/composables/useAssetManagement', () => ({ useAssetManagement: () => ({ buildingOptions: { value: [] }, equipmentScopeSpaces: { value: [] }, equipmentScopeGroups: { value: [] }, equipmentPage: { value: { items: [] } }, points: { value: [] }, ensureBuildingOptions: vi.fn(), loadEquipmentScope: vi.fn(), setEquipmentQuery: vi.fn(), selectEquipment: vi.fn() }) }))
vi.mock('@/utils/request', () => ({ requestErrorMessage: () => '当前账号没有权限执行此操作' }))

const stubs = {
  AdminPageHeader: { props: ['title', 'description'], template: '<header><h1>{{ title }}</h1><p>{{ description }}</p><slot /></header>' },
  'a-alert': { props: ['message'], template: '<div role="alert">{{ message }}</div>' },
  'a-table': { template: '<div><slot name="emptyText" /></div>' },
  'a-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'a-select': true, 'a-input': true, 'a-button': true, 'a-skeleton': true, 'a-drawer': true, 'a-modal': true,
  'a-descriptions': true, 'a-descriptions-item': true, 'a-tag': true, 'a-space': true, 'a-form': true, 'a-form-item': true, 'a-textarea': true,
  AssetStatusTag: true, DeviceProductDrawer: true, DeviceBindingWizard: true,
}

describe('device onboarding management pages', () => {
  it('keeps a permission failure visible on the product page instead of presenting an empty success', async () => {
    const wrapper = shallowMount(DeviceProductManagementPage, { global: { stubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('产品与测点模板')
    expect(wrapper.text()).toContain('当前账号没有权限执行此操作')
  })

  it('names the separate identity activation boundary on the discovery page', async () => {
    const wrapper = shallowMount(DeviceOnboardingPage, { global: { stubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('待绑定设备接入')
    expect(wrapper.text()).toContain('单独确认启用设备身份')
    expect(wrapper.text()).toContain('当前账号没有权限执行此操作')
  })
})
