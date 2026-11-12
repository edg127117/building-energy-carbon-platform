import { defineComponent, h, nextTick } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IndexPage from './IndexPage.vue'
import BuildingScene from '../../components/BuildingScene.vue'
import ProfessionalScenePanels from '../../components/ProfessionalScenePanels.vue'
import ScreenPeriodControl from '../../components/ScreenPeriodControl.vue'
import ScreenUsageChart from '../../components/ScreenUsageChart.vue'
import ScreenBusinessFlow from '../../components/ScreenBusinessFlow.vue'
import ScreenDeviceSummary from '../../components/ScreenDeviceSummary.vue'
import { PROTOTYPE_DEVICE_FRAME_MS } from '../../models/screen-prototype'
import ScreenMirrorChart from '../../components/ScreenMirrorChart.vue'
import ScreenPlanChart from '../../components/ScreenPlanChart.vue'
import ScreenSummaryCandidate from '../../components/ScreenSummaryCandidate.vue'
import { currentScreenPeriod } from '../../models/screen-period'
import type { ScreenRedesignData } from '../../models/screen-redesign'

const sceneCalls = vi.hoisted(() => ({ enter: vi.fn(), preset: vi.fn(), motion: vi.fn(), floor: vi.fn(), select: vi.fn(), playing: vi.fn() }))
vi.mock('../../components/BuildingScene.vue', () => ({ default: defineComponent({
  emits: ['ready', 'snapshot', 'hotspots'],
  setup(_, { expose, emit }) {
    expose({ enterSystem: sceneCalls.enter, preset: sceneCalls.preset, setFree: vi.fn(), setMotion: sceneCalls.motion, setTour: vi.fn(), reload: vi.fn(), setFloor: sceneCalls.floor, selectAsset: sceneCalls.select, setPlaying: sceneCalls.playing })
    nextTick(() => { emit('ready', true); emit('hotspots', ['hvac', 'elevator', 'lighting', 'fire'].map((id, i) => ({ id, visible: true, x: 800 + i * 100, y: 350 }))) })
    return () => h('div', { class: 'scene-test-stage' })
  },
}) }))
vi.mock('@/shared/charts/ChartView.vue', () => ({ default: defineComponent({ props: ['option', 'accessibleLabel'], setup: () => () => h('div', { class: 'chart-stub' }) }) }))

async function screen(data?: ScreenRedesignData, query = '', layoutVariant?: 'a' | 'b' | 'c') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/monitor/monitoring', component: IndexPage }] })
  await router.push('/monitor/monitoring' + query)
  await router.isReady()
  const wrapper = mount(IndexPage, { props: { data, layoutVariant }, attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}
describe('approved twelve-point prototype', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.useRealTimers())
  it('renders backend cumulative top values and same-cutoff comparison without altering monthly top values', async () => {
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-31T10:00:00+08:00'))
    const wrapper = await screen({
      annual: { year: '2026', throughMonth: 9, carbon: 129.456789, intensity: 6.472839 },
      previousMonth: { month: '2026-09', carbon: 23.946337, intensity: 0.670441 },
      composition: { period: { kind: 'year', value: '2026' }, data: { total: 129.456789, yearOverYear: -7.25, monthOverMonth: null, unit: 'tCO₂e', throughMonth: 9 } },
    }, '', 'b')
    expect(wrapper.findAll('.screen-metrics strong').map(item => item.text())).toEqual(['129.46', '6.47', '23.95', '0.67'])
    expect(wrapper.get('.composition-card').text()).toContain('同比·上年同期')
    expect(wrapper.get('.composition-card').text()).toContain('-7.25%')
    wrapper.unmount()
  })
  it('replaces all specified cards and keeps the full title as one heading', async () => {
    const wrapper = await screen()
    expect(wrapper.get('h1').text()).toBe('创新港大楼 · 建筑能碳监测管理平台')
    expect(wrapper.findAll('.screen-metrics article')).toHaveLength(4)
    expect(wrapper.findAll('.screen-metrics strong').map(item => item.text())).toEqual(['104.00', '5.20', '12.00', '0.60'])
    expect(wrapper.get('.electricity-source').text()).toContain('100%')
    expect(wrapper.get('.screen-metrics').text()).toContain('当年累计碳排放')
    expect(wrapper.get('.screen-metrics').text()).toContain('当年碳排放强度')
    expect(wrapper.get('.composition-card').text()).toContain('同比·上年全年')
    expect(wrapper.findAll('.comparison-values>div')).toHaveLength(1)
    expect(wrapper.get('.composition-card').text()).not.toContain('-2.8%')
    expect(wrapper.get('.screen-metrics').text()).toContain('上月累计碳排放')
    expect(wrapper.get('.screen-metrics').text()).toContain('上月碳排放强度')
    expect(wrapper.findAll('.screen-card')).toHaveLength(8)
    for (const title of ['碳排放计划与配额', '碳排放趋势', '用电趋势', '碳排放分项评分', '设备实时状态', '报警信息']) expect(wrapper.text()).toContain(title)
    for (const old of ['当日累计', '范围一', '天然气', '柴油', '外购热力', '功能碳排放占比', '设备排名', '低 COP', '全天预测']) expect(wrapper.text()).not.toContain(old)
    expect(wrapper.get('.intensity-card').text()).not.toContain('70.78')
    expect(wrapper.get('.alarm-card').text()).not.toContain('—')
    expect(wrapper.get('.device-card').text()).toContain('送风')
    wrapper.unmount()
  })
  it.each(['a', 'b', 'c'] as const)('布局候选%s接入对应两卡片设计，默认布局仍可恢复', async (variant) => {
    const wrapper = await screen(undefined, '', variant)
    const stage = wrapper.get('.scene-test-stage').element
    expect(wrapper.classes()).toContain('layout-' + variant)
    expect(wrapper.findAll('.screen-card')).toHaveLength(6)
    expect(wrapper.findAll('.summary-panel')).toHaveLength(2)
    expect(wrapper.findAllComponents(ScreenPeriodControl)).toHaveLength(6)
    expect(wrapper.find('.left-anchor .usage-card').exists()).toBe(true)
    expect(wrapper.find('.right-anchor .intensity-card').exists()).toBe(true)
    const summary = wrapper.findComponent(ScreenSummaryCandidate)
    expect(summary.props('variant')).toBe(variant)
    expect(summary.props('embedded')).toBe(true)
    expect(summary.props('devices').total).toBe(40)
    expect(wrapper.findComponent(ScreenUsageChart).props('expanded')).toBe(true)
    if (variant === 'b') expect(wrapper.findAll('.segmented button').map(button => button.text())).toEqual(['碳流图', '能流图'])
    expect(wrapper.findComponent(ScreenMirrorChart).props('expanded')).toBe(true)
    await wrapper.get('[aria-label="收起设备实时状态"]').trigger('click')
    expect(wrapper.get('.device-content').isVisible()).toBe(false)
    await wrapper.get('[aria-label="展开设备实时状态"]').trigger('click')
    expect(wrapper.get('.device-content').isVisible()).toBe(true)
    await wrapper.setProps({ layoutVariant: undefined })
    expect(wrapper.find('.left-anchor .intensity-card').exists()).toBe(true)
    expect(wrapper.find('.right-anchor .usage-card').exists()).toBe(true)
    expect(wrapper.findComponent(ScreenSummaryCandidate).exists()).toBe(false)
    expect(wrapper.findComponent(ScreenUsageChart).props('expanded')).toBe(false)
    expect(wrapper.get('.scene-test-stage').element).toBe(stage)
    wrapper.unmount()
  })
  it('defaults all six controls to year and the flow to carbon', async () => {
    const wrapper = await screen()
    const controls = wrapper.findAllComponents(ScreenPeriodControl)
    expect(controls).toHaveLength(6)
    expect(controls.every(control => control.props('modelValue').kind === 'year')).toBe(true)
    expect(wrapper.get('.segmented button[aria-pressed=true]').text()).toBe('碳流图')
    expect(wrapper.get('.business-flow-head').text()).toContain('业务用途楼层建筑合计')
    const graph = wrapper.findComponent(ScreenBusinessFlow).props('data')
    expect(graph.nodes.map((node: { name: string }) => node.name)).toContain('过道')
    expect(graph.nodes.filter((node: { depth: number }) => node.depth === 1)).toHaveLength(6)
    expect(graph.nodes.map((node: { name: string }) => node.name)).toContain('6楼')
    wrapper.unmount()
  })

  it('年视图不把环比冒充另一个年度同比，月视图分别展示去年同月和上月', async () => {
    const wrapper = await screen()
    expect(wrapper.get('.comparison-values').text()).toContain('同比·上年全年')
    expect(wrapper.findAll('.comparison-values>div')).toHaveLength(1)
    wrapper.findAllComponents(ScreenPeriodControl)[0].vm.$emit('update:modelValue', { kind: 'month', value: '2026-01' })
    await nextTick()
    expect(wrapper.findAll('.comparison-values>div')).toHaveLength(2)
    expect(wrapper.get('.comparison-values').text()).toContain('同比·去年同月-4.2%')
    expect(wrapper.get('.comparison-values').text()).toContain('环比·上月-2.8%')
    expect(wrapper.get('.comparison-values').text()).not.toContain('上年全年')
    wrapper.unmount()
  })
  it('starts at overview despite a cached deep link and exposes the restored hotspots', async () => {
    const wrapper = await screen(undefined, '?system=hvac')
    expect(wrapper.findAll('.system-hotspot')).toHaveLength(3)
    expect(wrapper.find('.system-hotspot[aria-label="进入消防系统"]').exists()).toBe(false)
    expect(wrapper.findComponent(ProfessionalScenePanels).exists()).toBe(false)
    expect(sceneCalls.enter).toHaveBeenCalledWith(null)
    expect(wrapper.find('.screen-metrics').exists()).toBe(true)
    await wrapper.get('.camera-views button:nth-child(4)').trigger('click')
    expect(sceneCalls.preset).toHaveBeenCalledWith('aerial')
    wrapper.unmount()
  })
  it.each(['hvac', 'elevator', 'lighting'])('从%s热点进入模型，切楼层并返回时保留总览期间与场景实例', async (topic) => {
    const wrapper = await screen({}, '', 'b')
    const canvas = wrapper.get('.scene-test-stage').element
    const periods = wrapper.findAllComponents(ScreenPeriodControl)
    periods[1].vm.$emit('update:modelValue', { kind: 'day', value: '2026-11-12' })
    await nextTick()
    const changes = wrapper.emitted('periodChange')?.length
    await wrapper.findAll('.system-hotspot')[['hvac', 'elevator', 'lighting'].indexOf(topic)].trigger('click')
    expect(sceneCalls.enter).toHaveBeenLastCalledWith(topic)
    expect(wrapper.get('.information-layer').isVisible()).toBe(false)
    expect(wrapper.find('.screen-clock').exists()).toBe(false)
    const renderer = wrapper.findComponent(BuildingScene)
    renderer.vm.$emit('snapshot', { topic, selected: null, floorFilter: '全部', playing: false, selectionRevision: 0, assets: [{ id: 'model-1', name: '楼层模型', system: topic, floor: 'F3' }] })
    await nextTick()
    const panel = wrapper.findComponent(ProfessionalScenePanels)
    expect(panel.props('assets')).toHaveLength(1)
    expect(panel.get('.energy-total').text()).not.toContain('—')
    expect(panel.get('.professional-tabs').text()).not.toContain('消防')
    panel.vm.$emit('system', 'fire')
    await nextTick()
    expect(sceneCalls.enter).toHaveBeenLastCalledWith(topic)
    await panel.get('select').setValue('F3')
    expect(sceneCalls.floor).toHaveBeenLastCalledWith('F3')
    await panel.get('.model-list-toggle').trigger('click')
    await panel.get('.model-list button').trigger('click')
    expect(sceneCalls.select).toHaveBeenLastCalledWith('model-1')
    panel.vm.$emit('playing', true)
    expect(sceneCalls.playing).toHaveBeenLastCalledWith(true)
    panel.vm.$emit('system', null)
    await nextTick()
    expect(sceneCalls.enter).toHaveBeenLastCalledWith(null)
    expect(wrapper.get('.information-layer').isVisible()).toBe(true)
    expect(wrapper.findAllComponents(ScreenPeriodControl)[1].props('modelValue')).toEqual({ kind: 'day', value: '2026-11-12' })
    expect(wrapper.emitted('periodChange')?.length).toBe(changes)
    expect(wrapper.get('.scene-test-stage').element).toBe(canvas)
    wrapper.unmount()
  })
  it('场景重载时清空旧模型快照，恢复后保持当前系统并过滤不可见热点', async () => {
    const wrapper = await screen({})
    const renderer = wrapper.findComponent(BuildingScene)
    renderer.vm.$emit('hotspots', [{ id: 'hvac', visible: false, x: 0, y: 0 }, { id: 'unknown', visible: true, x: 0, y: 0 }])
    await nextTick()
    expect(wrapper.findAll('.system-hotspot')).toHaveLength(0)
    renderer.vm.$emit('hotspots', [{ id: 'hvac', visible: true, x: 800, y: 350 }])
    await nextTick()
    await wrapper.get('.system-hotspot').trigger('click')
    renderer.vm.$emit('ready', false)
    renderer.vm.$emit('snapshot', null)
    await nextTick()
    expect(wrapper.findComponent(ProfessionalScenePanels).props('assets')).toEqual([])
    renderer.vm.$emit('ready', true)
    await nextTick()
    expect(sceneCalls.enter).toHaveBeenLastCalledWith('hvac')
    wrapper.unmount()
  })
  it('keeps side panels and both bottom summaries independently collapsible without recreating the scene', async () => {
    const wrapper = await screen(), stage = wrapper.get('.scene-test-stage').element
    await wrapper.get('.left-anchor .panel-toggle').trigger('click')
    await wrapper.get('[aria-label="收起设备状态"]').trigger('click')
    expect(wrapper.get('.device-card').isVisible()).toBe(false)
    expect(wrapper.get('.alarm-card').isVisible()).toBe(true)
    await wrapper.get('[aria-label="展开设备状态"]').trigger('click')
    expect(wrapper.get('.device-card').isVisible()).toBe(true)
    expect(wrapper.get('.scene-test-stage').element).toBe(stage)
    wrapper.unmount()
  })
  it('keeps month views populated in prototype mode', async () => {
    const wrapper = await screen()
    wrapper.findAllComponents(ScreenPeriodControl)[5].vm.$emit('update:modelValue', { kind: 'month', value: '2026-02' })
    await nextTick()
    expect(wrapper.findComponent(ScreenUsageChart).props('data').electricity).toHaveLength(28)
    expect(wrapper.get('.screen-metrics').text()).not.toContain('—')
    expect(wrapper.findComponent(ScreenBusinessFlow).props('data').total).toBe(104)
    wrapper.unmount()
  })
  it('accepts zero backend inputs and clears a series when its selected period differs', async () => {
    const period = currentScreenPeriod()
    const wrapper = await screen({
      annual: { year: period.value, carbon: 0, intensity: 1.2345 },
      composition: { period, data: { total: 0, yearOverYear: null, monthOverMonth: null, unit: 'tCO₂e' } },
      usage: { period, data: { electricity: [0, 10], temperature: [12, null], electricityUnit: 'kWh', temperatureUnit: '℃' } },
      alarms: { deviceAnomalies: 0, emissionWarnings: null },
    })
    expect(wrapper.get('.carbon-ring strong').text()).toBe('0')
    expect(wrapper.findAll('.screen-metrics strong').map(item => item.text())).toEqual(['0.00', '1.23', '—', '—'])
    expect(wrapper.get('.alarm-card').text()).toContain('0')
    expect(wrapper.findComponent(ScreenUsageChart).props('data').electricity[0]).toBe(0)
    expect(wrapper.findComponent(ScreenPlanChart).props('data')).toBeNull()
    wrapper.findAllComponents(ScreenPeriodControl)[5].vm.$emit('update:modelValue', { kind: 'month', value: period.value + '-01' })
    await nextTick()
    expect(wrapper.findComponent(ScreenUsageChart).props('data')).toBeNull()
    expect(wrapper.findAllComponents(ScreenPeriodControl)[0].props('modelValue')).toEqual(period)
    wrapper.unmount()
  })
  it('keeps the complete B status fields without importing prototype readings into live mode', async () => {
    const wrapper = await screen({}, '', 'b')
    const summary = wrapper.findComponent(ScreenSummaryCandidate)
    expect(summary.props('devices').speeds.map((entry: { count: number | null }) => entry.count)).toEqual([null, null, null, null])
    expect(summary.props('runtimeRanking')).toBeUndefined()
    expect(wrapper.findAll('.screen-metrics strong').every(item => item.text() === '—')).toBe(true)
    expect(wrapper.findComponent(ScreenPlanChart).props('data')).toBeNull()
    expect(wrapper.get('.status-groups').text()).toContain('自动')
    wrapper.unmount()
  })
  it('renders populated prototype cards and hides formula text', async () => {
    const wrapper = await screen()
    expect(wrapper.get('.plan-card').text()).toContain('计划消减率')
    expect(wrapper.get('.plan-card').text()).not.toContain('模拟')
    expect(wrapper.get('.plan-card').text()).not.toContain('年基准排放量 ×')
    expect(wrapper.get('.screen-metrics').text()).not.toContain('32')
    expect(wrapper.get('.intensity-card').text()).not.toContain('—')
    expect(wrapper.get('.screen-metrics').text()).toContain('104')
    expect(wrapper.findComponent(ScreenUsageChart).props('data').electricity).toHaveLength(12)
    expect(wrapper.findComponent(ScreenMirrorChart).props('data').dynamicEmissions.every((value: number) => value > 0)).toBe(true)
    wrapper.unmount()
  })
  it('cycles prototype status frames, pauses hidden updates, and stops when external data replaces them', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const wrapper = await screen()
    try {
      const summary = () => wrapper.findComponent(ScreenDeviceSummary)
      const firstTime = summary().props('updatedAt')
      expect(summary().props('data').on).toBe(25)
      await vi.advanceTimersByTimeAsync(PROTOTYPE_DEVICE_FRAME_MS)
      expect(summary().props('data').on).toBe(27)
      expect(summary().props('updatedAt')).toBeGreaterThan(firstTime)
      visibility.mockReturnValue('hidden')
      await vi.advanceTimersByTimeAsync(PROTOTYPE_DEVICE_FRAME_MS)
      expect(summary().props('data').on).toBe(27)
      const external = { total: 40, on: 0, off: 37, unknown: 3, modes: [], speeds: [] }
      await wrapper.setProps({ data: { devices: external } })
      await vi.advanceTimersByTimeAsync(PROTOTYPE_DEVICE_FRAME_MS)
      expect(summary().props('data')).toEqual(external)
      expect(summary().props('updatedAt')).toBeUndefined()
    } finally {
      wrapper.unmount()
      visibility.mockRestore()
    }
    expect(vi.getTimerCount()).toBe(0)
  })

})
