import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BuildingAccessManagementPage from './BuildingAccessManagementPage.vue'
vi.mock('@/composables/useBuildingAccessManagement', () => ({ useBuildingAccessManagement: () => ({ requests:{value:[]},users:{value:[]},buildings:{value:[]},status:{value:'PENDING'},loading:{value:false},error:{value:null},pending:{value:false},load:vi.fn().mockResolvedValue(undefined),loadOptions:vi.fn().mockResolvedValue(undefined),assignBuildings:vi.fn(),approve:vi.fn(),reject:vi.fn() }) }))
describe('building access management page', () => {
  it('separates direct assignment from request review and has no building CRUD', () => { const wrapper=mount(BuildingAccessManagementPage,{global:{stubs:{UserBuildingAssignmentDrawer:true,BuildingAccessReviewDrawer:true,'a-button':true,'a-alert':true,'a-select':true,'a-table':{template:'<div><slot /></div>'},'a-tag':true,'a-space':true}}}); expect(wrapper.text()).toContain('直接授权'); expect(wrapper.text()).toContain('申请审核'); expect(wrapper.text()).not.toContain('新建建筑') })
})
