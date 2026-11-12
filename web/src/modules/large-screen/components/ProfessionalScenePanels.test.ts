import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProfessionalScenePanels from './ProfessionalScenePanels.vue'
import ScreenPlot from './ScreenPlot.vue'
import { professionalDemo } from '../models/professional-demo'
vi.mock('./ScreenPlot.vue', () => ({ default: { props: ['periods', 'series', 'valueAxisUnit'], template: '<div />' } }))
describe('professional model panels', () => {
  it('跨楼层电梯模型在经过的楼层中可计数、选择，其他楼层保持空列表', async () => {
    const wrapper = mount(ProfessionalScenePanels, { props: { topic: 'elevator', assets: [{ id: 'EL-01', name: '电梯模型', system: 'elevator', floor: '多层', floors: [{ name: 'F1' }, { name: 'F3' }] }], selected: null, floor: 'F3', buildingName: 'A', playing: false } })
    expect(wrapper.findAll('.floor-row')[2].text()).toContain('1 组')
    await wrapper.get('.model-list-toggle').trigger('click')
    await wrapper.get('.model-list button').trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['EL-01'])
    await wrapper.setProps({ floor: 'F2' })
    expect(wrapper.find('.model-list button').exists()).toBe(false)
    expect(wrapper.text()).toContain('当前楼层无模型对象')
    wrapper.unmount()
  })
  it('展示左右面板数据，保留真实模型计数，并同步范围选择事件', async () => {
    const wrapper = mount(ProfessionalScenePanels, { props: { topic: 'hvac', assets: [{ id: 'E5_F5_DRAWN_001', name: '外机模型', system: 'hvac', floor: 'F5' }, { id: 'HVAC-INDOOR-DEMO', name: '内机模型', system: 'hvac', floor: 'F3' }], selected: null, floor: '全部', buildingName: 'A', playing: false } })
    expect(wrapper.get('.model-total').text()).toContain('2')
    expect(wrapper.text()).not.toContain('模拟数据')
    expect(wrapper.text()).not.toContain('正式运行状态')
    expect(wrapper.get('.asset-list').text()).toContain('运行正常')
    expect(wrapper.get('.asset-list').text()).toContain('通讯在线')
    expect(wrapper.get('.energy-total').text()).toContain(professionalDemo('hvac', '全部')!.today)
    expect(wrapper.findAll('.professional-tabs button').map(button => button.text())).toEqual(['电梯', '空调', '照明'])
    await wrapper.get('select').setValue('F3')
    expect(wrapper.emitted('floor')?.at(-1)).toEqual(['F3'])
    await wrapper.findAll('.kind-tabs button')[1].trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['HVAC-INDOOR-DEMO'])
    await wrapper.findAll('.professional-tabs button')[0].trigger('click')
    expect(wrapper.emitted('system')?.at(-1)).toEqual(['elevator'])
    wrapper.unmount()
  })
  it('系统与楼层切换同时更新指标和小时曲线，不受模型播放影响', async () => {
    const wrapper = mount(ProfessionalScenePanels, { props: { topic: 'hvac', assets: [], selected: null, floor: '全部', buildingName: 'A', playing: false } })
    const total = wrapper.get('.energy-total').text()
    await wrapper.setProps({ floor: 'F3' })
    expect(wrapper.get('.energy-total').text()).not.toBe(total)
    expect(wrapper.get('.energy-total').text()).toContain('F3 层')
    await wrapper.setProps({ topic: 'lighting' })
    const lighting = professionalDemo('lighting', 'F3')!
    expect(wrapper.get('.energy-total').text()).toContain(lighting.today)
    expect(wrapper.findComponent(ScreenPlot).props('series')[0].values).toEqual(lighting.hours)
    expect(wrapper.findComponent(ScreenPlot).props('periods')).toHaveLength(24)
    expect(wrapper.findComponent(ScreenPlot).props('valueAxisUnit')).toBe('kWh')
    await wrapper.setProps({ playing: true })
    expect(wrapper.get('.energy-total').text()).toContain(lighting.today)
    wrapper.unmount()
  })
  it.each(['hvac', 'elevator', 'lighting'])('%s固定样例的楼层合计、小时合计与总览一致，未来小时留空', topic => {
    const total = professionalDemo(topic, '全部')!
    const floorTotals = ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'Roof'].map(floor => professionalDemo(topic, floor)!)
    expect(floorTotals.reduce((sum, floor) => sum + Number(floor.today), 0)).toBeCloseTo(Number(total.today), 2)
    expect(floorTotals.reduce((sum, floor) => sum + Number(floor.month), 0)).toBeCloseTo(Number(total.month), 2)
    expect(total.hours.reduce<number>((sum, value) => sum + (value ?? 0), 0)).toBeCloseTo(Number(total.today), 2)
    expect(total.hours.slice(17)).toEqual(Array(7).fill(null))
    expect(professionalDemo(topic, '全部')).toEqual(total)
    expect(professionalDemo('fire', '全部')).toBeNull()
    expect(professionalDemo('elevator', 'Roof')?.change).toBeNull()
  })
})
