import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DeviceManagementPage from './DeviceManagementPage.vue'

vi.mock('@/composables/useAssetManagement', () => ({
  useAssetManagement: () => ({
    buildingQuery: { value: {} }, buildingPage: { value: {} }, buildingsLoading: { value: false }, buildingsError: { value: null }, selectedBuilding: { value: null }, spaces: { value: [] }, systemGroups: { value: {} }, buildingContextLoading: { value: false }, buildingContextError: { value: null },
    equipmentQuery: { value: { page: 1, size: 20 } }, equipmentPage: { value: { page: 1, size: 20, total: 0, items: [] } }, equipmentLoading: { value: false }, equipmentError: { value: { message: '当前状态不允许此操作，请刷新后重试', forbidden: false } }, selectedEquipment: { value: null }, points: { value: [] }, equipmentDetailLoading: { value: false }, equipmentDetailError: { value: null }, equipmentScopeSpaces: { value: [] }, equipmentScopeGroups: { value: [] }, equipmentScopeLoading: { value: false }, buildingOptions: { value: [] }, pending: { value: new Set() },
    ensureBuildingOptions: vi.fn().mockResolvedValue(undefined), loadEquipment: vi.fn().mockResolvedValue(undefined), setEquipmentQuery: vi.fn().mockResolvedValue(undefined), loadEquipmentScope: vi.fn().mockResolvedValue(undefined), selectEquipment: vi.fn().mockResolvedValue(undefined), saveEquipment: vi.fn().mockResolvedValue(undefined), removeEquipment: vi.fn().mockResolvedValue(undefined), savePoint: vi.fn().mockResolvedValue(undefined), removePoint: vi.fn().mockResolvedValue(undefined),
  }),
}))

const stubs = {
  AdminPageHeader: { props: ['title', 'description'], template: '<header><h1>{{ title }}</h1><p>{{ description }}</p><slot /></header>' }, AssetEquipmentDrawer: true, AssetPointDrawer: true, AssetStatusTag: true,
  'a-button': { template: '<button><slot /></button>' }, 'a-alert': { props: ['message'], template: '<div>{{ message }}<slot /></div>' }, 'a-input': true, 'a-select': true, 'a-skeleton': true, 'a-table': { template: '<div><slot /></div>' }, 'a-empty': { template: '<div><slot /></div>' }, 'a-space': { template: '<span><slot /></span>' }, 'a-drawer': { template: '<section><slot /></section>' }, 'a-descriptions': { template: '<div><slot /></div>' }, 'a-descriptions-item': { template: '<div><slot /></div>' }, 'a-tag': { template: '<span><slot /></span>' },
}

describe('device management page', () => {
  it('uses a real filtered device table and keeps product onboarding outside this work package', () => {
    const wrapper = mount(DeviceManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('设备与测点')
    expect(wrapper.text()).toContain('新建设备')
    expect(wrapper.text()).toContain('当前状态不允许此操作，请刷新后重试')
    expect(wrapper.text()).not.toContain('接入向导')
  })
})
