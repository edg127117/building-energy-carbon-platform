import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput, ElPagination, ElSelect } from '@/shared/ui'
import { listBuildings, listEquipment, listSpaces, listSystemGroups } from '../api/assets'
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
    wrapper = mount(EquipmentPointPage, { global: { directives: { loading: {} } } })
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
})
