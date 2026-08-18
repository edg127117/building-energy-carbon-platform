import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BuildingManagementPage from './BuildingManagementPage.vue'

vi.mock('@/composables/useAssetManagement', () => ({
  useAssetManagement: () => ({
    buildingQuery: { value: { page: 1, size: 20 } }, buildingPage: { value: { page: 1, size: 20, total: 0, items: [] } }, buildingsLoading: { value: false }, buildingsError: { value: { message: '当前账号没有权限执行此操作', forbidden: true } },
    selectedBuilding: { value: null }, spaces: { value: [] }, systemGroups: { value: { page: 1, size: 100, total: 0, items: [] } }, buildingContextLoading: { value: false }, buildingContextError: { value: null },
    equipmentQuery: { value: {} }, equipmentPage: { value: {} }, equipmentLoading: { value: false }, equipmentError: { value: null }, selectedEquipment: { value: null }, points: { value: [] }, equipmentDetailLoading: { value: false }, equipmentDetailError: { value: null }, equipmentScopeSpaces: { value: [] }, equipmentScopeGroups: { value: [] }, equipmentScopeLoading: { value: false }, buildingOptions: { value: [] }, pending: { value: new Set() },
    loadBuildings: vi.fn().mockResolvedValue(undefined), setBuildingQuery: vi.fn().mockResolvedValue(undefined), selectBuilding: vi.fn().mockResolvedValue(undefined), saveBuilding: vi.fn().mockResolvedValue(undefined), removeBuilding: vi.fn().mockResolvedValue(undefined), saveSpace: vi.fn().mockResolvedValue(undefined), removeSpace: vi.fn().mockResolvedValue(undefined), saveSystemGroup: vi.fn().mockResolvedValue(undefined), removeSystemGroup: vi.fn().mockResolvedValue(undefined),
  }),
}))

const stubs = {
  AdminPageHeader: { props: ['title', 'description'], template: '<header><h1>{{ title }}</h1><p>{{ description }}</p><slot /></header>' }, AssetBuildingDrawer: true, AssetSpaceDrawer: true, AssetSpaceTree: true, AssetSystemGroupDrawer: true, AssetStatusTag: true,
  'a-button': { template: '<button><slot /></button>' }, 'a-alert': { props: ['message'], template: '<div>{{ message }}<slot /></div>' }, 'a-input': true, 'a-skeleton': true, 'a-table': { template: '<div><slot /></div>' }, 'a-empty': { template: '<div><slot /></div>' }, 'a-space': { template: '<span><slot /></span>' }, 'a-tabs': { template: '<div><slot /></div>' }, 'a-tab-pane': { template: '<section><slot /></section>' },
}

describe('building management page', () => {
  it('keeps building, space and system operations inside the controlled asset page and exposes permission feedback', () => {
    const wrapper = mount(BuildingManagementPage, { global: { stubs } })
    expect(wrapper.text()).toContain('建筑与空间')
    expect(wrapper.text()).toContain('新建建筑')
    expect(wrapper.text()).toContain('当前账号没有权限执行此操作')
    expect(wrapper.text()).not.toContain('产品型号')
  })
})
