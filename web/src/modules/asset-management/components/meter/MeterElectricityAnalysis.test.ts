import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { getMeterElectricity } from '../../api/meter-electricity'
import MeterElectricityAnalysis from './MeterElectricityAnalysis.vue'

vi.mock('../../api/meter-electricity', () => ({ getMeterElectricity: vi.fn() }))

const meters = [
  { equipmentId: 'M-027', equipmentCode: 'IDU1', equipmentName: '单项电表027', buildingId: 'BLD001', category: 'ELECTRIC_METER' },
  { equipmentId: 'M-011', equipmentCode: 'ODU1', equipmentName: '三项电表011', buildingId: 'BLD001', category: 'ELECTRIC_METER' },
] as never[]

function view(id: string) {
  return {
    equipmentId: id, equipmentCode: id === 'M-027' ? 'IDU1' : 'ODU1', equipmentName: id,
    buildingId: 'BLD001', pointCode: 'EPP', unit: 'kWh', timeZone: 'Asia/Shanghai', boundaryWindowMinutes: 6,
    currentCoverage: { equipmentId: id, revision: 2, effectiveAt: '2026-09-29T08:00:00Z',
      installationSpaceId: 'S3', installationSpaceName: '三楼', scopeLabel: '现场确认范围', reason: '已确认',
      targets: [
        { equipmentId: 'IDU15', equipmentCode: 'IDU15', equipmentName: 'B314-3 内机', spaceId: 'B314', spaceName: 'B314', active: true },
        { equipmentId: 'IDU7', equipmentCode: 'IDU7', equipmentName: 'B314-2 内机', spaceId: 'B314', spaceName: 'B314', active: true },
        { equipmentId: 'IDU6', equipmentCode: 'IDU6', equipmentName: 'B314-1 内机', spaceId: 'B314', spaceName: 'B314', active: true },
      ],
      quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY' },
    days: [{ date: '2026-09-29', kwh: 1.91, status: 'AVAILABLE', reason: 'NEAR_MIDNIGHT_SAMPLES',
      startSampleTime: 1790611080000, endSampleTime: 1790697480000, changeKwh: null, changePercent: null }],
    periodSummary: { measuredKwh: 1.91, availableDays: 1, requestedDays: 7 },
  }
}

describe('单表用电分析', () => {
  it('按选中的电表查询并显示当前档案，不在页面相加两表日量', async () => {
    vi.mocked(getMeterElectricity).mockImplementation(async id => view(id) as never)
    const wrapper = mount(MeterElectricityAnalysis, {
      props: { meters, initialMeterId: 'M-027' },
      global: { stubs: { ChartView: true } },
    })
    await flushPromises()
    expect(getMeterElectricity).toHaveBeenCalledWith('M-027', 'BLD001', 7)
    expect(wrapper.text()).toContain('1.91')
    expect(wrapper.text()).toContain('B314-3 内机')
    expect(wrapper.text()).toContain('三楼')
    expect(wrapper.text()).toContain('027 与 011 不相加')
    expect(wrapper.find('.subitem-row').text()).toContain('B314')
    expect(wrapper.find('.subitem-row').text()).toContain('1.91 kWh')
    expect(wrapper.find('.subitem-row').text()).toContain('3 台设备共同计量')
    expect(wrapper.findAll('.subitem-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('已计算 1/7 天')
    expect(wrapper.text()).not.toContain('0 kWh')

    const otherMeter = wrapper.findAll('.meter-tree-item').find(item => item.text().includes('三项电表011'))
    await otherMeter!.trigger('click')
    await flushPromises()
    expect(getMeterElectricity).toHaveBeenCalledWith('M-011', 'BLD001', 7)
    wrapper.unmount()
  })

  it('全部日期缺数时不把房间电量写成零', async () => {
    const missing = view('M-027')
    missing.days[0].kwh = null as never
    missing.days[0].status = 'MISSING'
    missing.periodSummary = { measuredKwh: null as never, availableDays: 0, requestedDays: 7 }
    vi.mocked(getMeterElectricity).mockResolvedValue(missing as never)
    const wrapper = mount(MeterElectricityAnalysis, {
      props: { meters, initialMeterId: 'M-027' },
      global: { stubs: { ChartView: true } },
    })
    await flushPromises()
    expect(wrapper.find('.subitem-row').text()).toContain('B314')
    expect(wrapper.find('.subitem-row').text()).not.toContain('0 kWh')
    expect(wrapper.find('.breakdown-center').text()).toContain('—')
    wrapper.unmount()
  })

  it('真实计算结果为零时显示零电量但不计算占比', async () => {
    const zero = view('M-027')
    zero.days[0].kwh = 0
    zero.periodSummary = { measuredKwh: 0, availableDays: 1, requestedDays: 7 }
    vi.mocked(getMeterElectricity).mockResolvedValue(zero as never)
    const wrapper = mount(MeterElectricityAnalysis, {
      props: { meters, initialMeterId: 'M-027' },
      global: { stubs: { ChartView: true } },
    })
    await flushPromises()
    expect(wrapper.find('.subitem-row').text()).toContain('0.00 kWh')
    expect(wrapper.find('.subitem-row').text()).not.toContain('100%')
    wrapper.unmount()
  })
})
