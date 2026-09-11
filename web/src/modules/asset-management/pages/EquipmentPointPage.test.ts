import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput, ElPagination, ElSelect, ElTabs } from '@/shared/ui'
import { getEquipment, listBuildings, listEquipment, listEquipmentPoints, listSpaces, listSystemGroups } from '../api/assets'
import EquipmentPointPage from './EquipmentPointPage.vue'

vi.mock('../api/assets', () => ({
  createBuilding: vi.fn(),
  createEquipment: vi.fn(),
  createSpace: vi.fn(),
  createSystemGroup: vi.fn(),
  deleteBuilding: vi.fn(),
  deleteEquipment: vi.fn(),
  deleteEquipmentPoint: vi.fn(),
  deleteSpace: vi.fn(),
  deleteSystemGroup: vi.fn(),
  getBuilding: vi.fn(),
  getEquipment: vi.fn(),
  listBuildings: vi.fn(),
  listEquipment: vi.fn(),
  listEquipmentPoints: vi.fn(),
  listSpaces: vi.fn(),
  listSystemGroups: vi.fn(),
  updateBuilding: vi.fn(),
  updateEquipment: vi.fn(),
  updateEquipmentPoint: vi.fn(),
  updateSpace: vi.fn(),
  updateSystemGroup: vi.fn()
}))

let wrapper: ReturnType<typeof mount>
const submit = async () => { await wrapper.find('form').trigger('submit'); await flushPromises() }
const select = async (index: number, value: string | undefined) => {
  const field = wrapper.findAllComponents(ElSelect)[index]
  field.vm.$emit('update:modelValue', value)
  field.vm.$emit('change', value)
  await flushPromises()
}

describe('设备列表筛选', () => {
  beforeEach(async () => {
    vi.resetAllMocks()
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 0, items: [] })
    vi.mocked(listBuildings).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
    vi.mocked(listSpaces).mockResolvedValue([])
    vi.mocked(listSystemGroups).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
    wrapper = mount(EquipmentPointPage, { attachTo: document.body, global: { directives: { loading: {} } } })
    await flushPromises()
  })
  afterEach(() => wrapper.unmount())

  it('提交组合筛选，翻页保留已提交条件而不是输入中的草稿', async () => {
    await select(0, 'B1')
    await select(1, 'S1')
    await select(2, 'G1')
    const inputs = wrapper.findAllComponents(ElInput)
    await inputs[0].setValue(' WCR ')
    await inputs[1].setValue(' 冷水机组 ')
    expect(listEquipment).toHaveBeenCalledTimes(1)
    await submit()
    expect(listEquipment).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, buildingId: 'B1', spaceId: 'S1', systemGroupId: 'G1', typeCode: 'WCR', keyword: '冷水机组' }))
    await inputs[1].setValue('未提交')
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(listEquipment).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, keyword: '冷水机组' }))
  })

  it('切换建筑清除空间和系统，重置清除全部筛选并回到第一页', async () => {
    await select(0, 'B1')
    await select(1, 'S1')
    await select(2, 'G1')
    await select(0, 'B2')
    await submit()
    expect(listEquipment).toHaveBeenLastCalledWith(expect.objectContaining({ buildingId: 'B2', spaceId: undefined, systemGroupId: undefined }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '重置')!.trigger('click')
    await flushPromises()
    expect(listEquipment).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, buildingId: undefined, spaceId: undefined, systemGroupId: undefined, typeCode: undefined, keyword: undefined }))
    expect(wrapper.findAllComponents(ElSelect)[1].props('disabled')).toBe(true)
  })
  it('查看测点直接进入测点标签，再次查看档案恢复台账标签', async () => {
    const equipment = { equipmentId: 'E1', equipmentName: '测试设备', equipmentCode: 'E-01', typeCode: 'WCR', status: 'ACTIVE', identities: [], pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [equipment as never] })
    vi.mocked(getEquipment).mockResolvedValue(equipment as never)
    vi.mocked(listEquipmentPoints).mockResolvedValue([])
    await submit()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看测点')!.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElTabs).props('modelValue')).toBe('points')
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['台账信息', '设备测点', '接入信息', '技术参数', '逻辑关系', '维护记录'])
    wrapper.findComponent({ name: 'ElDrawer' }).vm.$emit('update:modelValue', false)
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看档案')!.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElTabs).props('modelValue')).toBe('archive')
  })

  it('待建设标签不发送新请求，参数只读且无权限时不展示编辑入口', async () => {
    const equipment = { equipmentId: 'E1', equipmentName: '测试设备', equipmentCode: 'E-01', typeCode: 'WCR', status: 'ACTIVE', identities: [], ratedCapacity: 0, ratedPower: null, designCop: 5, pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [equipment as never] })
    vi.mocked(getEquipment).mockResolvedValue(equipment as never)
    vi.mocked(listEquipmentPoints).mockResolvedValue([])
    await submit()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看档案')!.trigger('click')
    await flushPromises()
    for (const name of ['逻辑关系', '维护记录']) {
      await wrapper.findAll('[role="tab"]').find(tab => tab.text() === name)!.trigger('click')
      await flushPromises()
      expect(wrapper.findAll('[role="tabpanel"]').find(panel => panel.isVisible())!.text()).toBe('待建设')
    }
    await wrapper.findAll('[role="tab"]').find(tab => tab.text() === '技术参数')!.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElTabs).props('modelValue')).toBe('parameters')
    const panel = wrapper.find('#pane-parameters')
    expect(panel.isVisible()).toBe(true)
    expect(panel.text()).toContain('额定容量0')
    expect(panel.text()).toContain('额定功率—')
    expect(panel.text()).toContain('技术参数为只读')
    expect(panel.findAll('input')).toHaveLength(0)
    expect(wrapper.findAllComponents(ElButton).some(button => button.text() === '编辑设备')).toBe(false)
    expect(getEquipment).toHaveBeenCalledTimes(1)
    expect(listEquipmentPoints).toHaveBeenCalledTimes(1)
  })

})
