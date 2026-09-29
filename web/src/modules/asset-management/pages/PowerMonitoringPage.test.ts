import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ElButton } from '@/shared/ui'
import { getEquipment, listBuildings, listEquipment, listEquipmentPoints, listSpaces, listSystemGroups } from '../api/assets'
import PowerMonitoringPage from './PowerMonitoringPage.vue'

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
  getEquipmentReadings: vi.fn().mockResolvedValue({ equipmentId: 'M-3P', generatedAt: Date.now(), points: [] }),
  getEquipmentTrendHistory: vi.fn().mockResolvedValue({ records: [] }),
  listBuildings: vi.fn(),
  listEquipment: vi.fn(),
  listEquipmentPoints: vi.fn(),
  listSpaces: vi.fn(),
  listSystemGroups: vi.fn(),
  updateBuilding: vi.fn(),
  updateEquipment: vi.fn(),
  updateEquipmentPoint: vi.fn(),
  updateSpace: vi.fn(),
  updateSystemGroup: vi.fn(),
}))

describe('电力监控页面（PowerMonitoringPage）', () => {
  it('仅聚合展示监测采集电表，支持三相 3P / 单相 1P 快捷筛选与右侧实时走势抽屉唤起', async () => {
    const items = [
      { equipmentId: 'E-IDU', equipmentName: '101室内机', equipmentCode: 'IDU-01', typeCode: 'IDU', spaceName: '101室', status: 'ACTIVE', identities: [], pointSummary: { total: 4, required: 0, configuredRequired: 0 } },
      { equipmentId: 'M-3P', equipmentName: '外机三相总表', equipmentCode: 'MTR-3P', typeCode: '3P_METER', spaceName: '楼顶机房', status: 'ACTIVE', identities: [], pointSummary: { total: 12, required: 0, configuredRequired: 0 } },
      { equipmentId: 'M-1P', equipmentName: '101室单相电表', equipmentCode: 'MTR-1P', typeCode: '1P_METER', spaceName: '101室', status: 'ACTIVE', identities: [], pointSummary: { total: 6, required: 0, configuredRequired: 0 } },
    ]
    vi.mocked(listEquipment).mockResolvedValue({ page: 1, size: 100, total: 3, items: items as never })
    vi.mocked(listBuildings).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
    vi.mocked(listSpaces).mockResolvedValue([])
    vi.mocked(listSystemGroups).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
    vi.mocked(getEquipment).mockResolvedValue(items[1] as never)
    vi.mocked(listEquipmentPoints).mockResolvedValue([])

    const wrapper = mount(PowerMonitoringPage, {
      attachTo: document.body,
      global: { directives: { loading: {} } },
    })
    await flushPromises()

    expect(wrapper.find('h1').text()).toBe('电力监控')
    expect(wrapper.text()).toContain('外机三相总表')
    expect(wrapper.text()).toContain('101室单相电表')
    expect(wrapper.text()).not.toContain('101室内机')

    const pill3P = wrapper.findAll('.phase-pill').find(btn => btn.text().includes('三相电表 3P'))
    expect(pill3P).toBeDefined()
    await pill3P!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('外机三相总表')
    expect(wrapper.text()).not.toContain('101室单相电表')

    const openBtn = wrapper.findAllComponents(ElButton).find(btn => btn.text() === '查看实时看板与走势')
    expect(openBtn).toBeDefined()
    await openBtn!.trigger('click')
    await flushPromises()

    expect(getEquipment).toHaveBeenCalledWith('M-3P')
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(true)
    wrapper.unmount()
  })
})
