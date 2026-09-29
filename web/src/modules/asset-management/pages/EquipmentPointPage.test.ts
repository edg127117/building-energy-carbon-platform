import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput, ElPagination, ElSelect, ElTabs } from '@/shared/ui'
import { getEquipment, listBuildings, listEquipment, listEquipmentPoints, listSpaces, listSystemGroups } from '../api/assets'
import { getMeterCoverage, listMeterCoverageCandidates, listMeterCoverages, listMeterCoverageHistory, updateMeterCoverage } from '../api/meter-coverage'
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
vi.mock('../api/meter-coverage', () => ({
  getMeterCoverage: vi.fn(),
  listMeterCoverageCandidates: vi.fn(),
  listMeterCoverageHistory: vi.fn(),
  listMeterCoverages: vi.fn(),
  updateMeterCoverage: vi.fn(),
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
    vi.mocked(listMeterCoverages).mockResolvedValue([])
    vi.mocked(getMeterCoverage).mockResolvedValue({ equipmentId: 'M1', revision: 1, effectiveAt: null, installationSpaceId: null, installationSpaceName: null, scopeLabel: null, reason: null, targets: [], quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY' })
    vi.mocked(listMeterCoverageCandidates).mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
    vi.mocked(listMeterCoverageHistory).mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
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
    const equipment = { equipmentId: 'E1', equipmentName: '测试设备', equipmentCode: 'E-01', typeCode: 'WCR', category: 'CHILLER', status: 'ACTIVE', identities: [], pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
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
    const equipment = { equipmentId: 'E1', equipmentName: '测试设备', equipmentCode: 'E-01', typeCode: 'WCR', category: 'CHILLER', status: 'ACTIVE', identities: [], ratedCapacity: 0, ratedPower: null, designCop: 5, pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
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

  it('监测采集设备（电表）在静态台账抽屉中展示静态测点表、隐藏暖通专属参数并提供电力监控跳转入口', async () => {
    const meter = {
      equipmentId: 'M1',
      equipmentName: '变压器进线三相电表',
      equipmentCode: 'MTR-01',
      typeCode: 'ELECTRIC_METER_3P',
      category: 'ELECTRIC_METER',
      status: 'ACTIVE',
      identities: [],
      ratedCapacity: 120,
      ratedPower: 15,
      designCop: 4.2,
      pointSummary: { total: 12, required: 0, configuredRequired: 0 },
      allowedActions: [],
    }
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [meter as never] })
    vi.mocked(getEquipment).mockResolvedValue(meter as never)
    vi.mocked(listEquipmentPoints).mockResolvedValue([
      { pointId: 'P1', pointName: '正向有功总电能', pointCode: 'EPP', unit: 'kWh', required: true, forCalculation: true } as never,
    ])

    await submit()
    const powerJumpBtn = wrapper.findAllComponents(ElButton).find(button => button.text() === '去电力监控查看走势')
    expect(powerJumpBtn).toBeDefined()

    const pointsBtn = wrapper.findAllComponents(ElButton).find(button => button.text() === '查看测点')
    expect(pointsBtn).toBeDefined()
    await pointsBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.findComponent(ElTabs).props('modelValue')).toBe('points')
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toContain('设备测点')
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toContain('电表档案')
    expect(wrapper.text()).toContain('正向有功总电能')
    expect(wrapper.text()).toContain('当前仅展示静态测点配置与计算标识')

    await wrapper.findAll('[role="tab"]').find(tab => tab.text() === '技术参数')!.trigger('click')
    await flushPromises()
    const panel = wrapper.find('#pane-parameters')
    expect(panel.text()).toContain('额定功率15')
    expect(panel.text()).not.toContain('额定容量')
    expect(panel.text()).not.toContain('设计性能系数')
  })

  it('unknown categories keep an archive entry without acquiring monitor or meter functionality from names', async () => {
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [{ equipmentId: 'U1', equipmentName: 'METER 空调', typeCode: 'IDU', category: 'UNKNOWN', pointSummary: { total: 0, required: 0, configuredRequired: 0 } } as never] })
    await wrapper.setProps({ ledgerCategory: 'BUSINESS' })
    await submit()
    expect(wrapper.text()).toContain('类型待配置')
    expect(wrapper.text()).toContain('查看档案')
    expect(wrapper.text()).not.toContain('去暖通监控查看运行')
    expect(wrapper.text()).not.toContain('去电力监控查看走势')
    expect(listMeterCoverages).not.toHaveBeenCalled()
  })

  it('按用能设备台账（BUSINESS）与监测采集设备（METER）分类隔离列表数据', async () => {
    const items = [
      { equipmentId: 'E-IDU', equipmentName: '101室内机', equipmentCode: 'IDU-01', typeCode: 'IDU', category: 'INDOOR_UNIT', status: 'ACTIVE', identities: [], pointSummary: { total: 4, required: 0, configuredRequired: 0 } },
      { equipmentId: 'M-3P', equipmentName: '外机三相电表', equipmentCode: 'MTR-3P', typeCode: 'ELECTRIC_METER_3P', category: 'ELECTRIC_METER', status: 'ACTIVE', identities: [], pointSummary: { total: 12, required: 0, configuredRequired: 0 } },
    ]
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 2, items: items as never })

    const businessWrapper = mount(EquipmentPointPage, {
      props: { ledgerCategory: 'BUSINESS' },
      global: { directives: { loading: {} } },
    })
    await flushPromises()
    expect(businessWrapper.find('h1').text()).toBe('用能设备台账')
    expect(businessWrapper.text()).toContain('101室内机')
    expect(businessWrapper.text()).not.toContain('外机三相电表')
    for (const label of ['覆盖范围', '被测设备数量', '电表安装位置']) {
      expect(businessWrapper.text()).not.toContain(label)
    }
    businessWrapper.unmount()

    const meterWrapper = mount(EquipmentPointPage, {
      props: { ledgerCategory: 'METER' },
      global: { directives: { loading: {} } },
    })
    await flushPromises()
    expect(meterWrapper.find('h1').text()).toBe('监测采集设备')
    expect(meterWrapper.text()).toContain('外机三相电表')
    expect(meterWrapper.text()).not.toContain('101室内机')
    for (const label of ['覆盖范围', '被测设备数量', '电表安装位置']) {
      expect(meterWrapper.text()).toContain(label)
    }
    await meterWrapper.setProps({ ledgerCategory: 'BUSINESS' })
    await flushPromises()
    expect(meterWrapper.text()).toContain('101室内机')
    for (const label of ['覆盖范围', '被测设备数量', '电表安装位置']) {
      expect(meterWrapper.text()).not.toContain(label)
    }
    meterWrapper.unmount()
  })

  it('批量展示表计独立安装位置与一表多设备数量', async () => {
    await wrapper.setProps({ ledgerCategory: 'METER' })
    const meter = { equipmentId: 'M1', equipmentName: '总表', equipmentCode: 'M-1', typeCode: 'ELECTRIC_METER_3P', category: 'ELECTRIC_METER', status: 'ACTIVE', identities: [], pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [meter as never] })
    vi.mocked(listMeterCoverages).mockResolvedValue([{ equipmentId: 'M1', revision: 2, effectiveAt: '2026-09-29T10:00:00Z', installationSpaceId: 'S1', installationSpaceName: '专用配电间', scopeLabel: '冷站总表范围', reason: '初始化', targets: [
      { equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '冷机一号', spaceId: 'S2', spaceName: '机房', active: true },
      { equipmentId: 'E2', equipmentCode: 'EQ-2', equipmentName: '冷机二号', spaceId: 'S2', spaceName: '机房', active: true },
    ], quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY' }])
    await submit()
    await flushPromises()
    expect(listMeterCoverages).toHaveBeenCalledWith({ buildingId: undefined, equipmentIds: ['M1'] })
    expect(wrapper.text()).toContain('冷站总表范围')
    expect(wrapper.text()).toContain('专用配电间')
    expect(wrapper.text()).toContain('被测设备数量')
    expect(wrapper.text()).toContain('2')
  })

  it('档案保存允许一表多设备并按预期版本提交', async () => {
    const meter = { equipmentId: 'M1', equipmentName: '总表', equipmentCode: 'M-1', typeCode: 'ELECTRIC_METER_3P', category: 'ELECTRIC_METER', buildingId: 'B1', status: 'ACTIVE', identities: [], pointSummary: { total: 0, required: 0, configuredRequired: 0 }, allowedActions: [] }
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 20, total: 1, items: [meter as never] })
    vi.mocked(getEquipment).mockResolvedValue(meter as never)
    vi.mocked(listMeterCoverageCandidates).mockResolvedValue({ page: 1, size: 10, total: 2, items: [
      { equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '冷机一号', spaceId: null, spaceName: null, active: true },
      { equipmentId: 'E2', equipmentCode: 'EQ-2', equipmentName: '冷机二号', spaceId: null, spaceName: null, active: true },
    ] })
    vi.mocked(updateMeterCoverage).mockResolvedValue({ equipmentId: 'M1', revision: 2, effectiveAt: null, installationSpaceId: null, installationSpaceName: null, scopeLabel: '冷站范围', reason: '确认覆盖设备', targets: [
      { equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '冷机一号', spaceId: null, spaceName: null, active: true },
      { equipmentId: 'E2', equipmentCode: 'EQ-2', equipmentName: '冷机二号', spaceId: null, spaceName: null, active: true },
    ], quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY' })
    await submit()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看档案')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('[role="tab"]').find(tab => tab.text() === '电表档案')!.trigger('click')
    await flushPromises()
    const panel = wrapper.findComponent({ name: 'MeterCoveragePanel' })
    await panel.findComponent({ name: 'ElInput' }).setValue('冷站范围')
    const reason = panel.findAllComponents(ElInput)[2]
    await reason.setValue('确认覆盖设备')
    panel.findComponent({ name: 'ElCheckboxGroup' }).vm.$emit('update:modelValue', ['E1', 'E2'])
    await flushPromises()
    await panel.findAllComponents(ElButton).find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(updateMeterCoverage).toHaveBeenCalledWith('M1', expect.objectContaining({ expectedRevision: 1, scopeLabel: '冷站范围', targetEquipmentIds: ['E1', 'E2'], reason: '确认覆盖设备' }))
  })
})
