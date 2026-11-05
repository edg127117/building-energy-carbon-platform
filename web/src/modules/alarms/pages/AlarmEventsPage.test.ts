import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = vi.hoisted(() => ({ path: '/operations/alarms/liveAlarms', query: { buildingId: 'B1', type: 'EMISSION' } as Record<string, string>, replace: vi.fn(), push: vi.fn() }))
const session = vi.hoisted(() => ({ menus: [{ path: '/operations/alarms/liveAlarms', status: 1, visible: 1 }] }))
const api = vi.hoisted(() => ({ buildings: vi.fn(), summary: vi.fn(), events: vi.fn(), event: vi.fn(), acknowledge: vi.fn(), note: vi.fn(), silence: vi.fn(), close:vi.fn(), trend:vi.fn() }))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  return {
    useRoute: () => reactive(routeState),
    useRouter: () => ({ replace: routeState.replace, push: routeState.push }),
    RouterLink: { props: ['to'], template: '<a><slot /></a>' },
  }
})
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => session }))
vi.mock('@/modules/dashboard/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/dashboard/public')>()), DaikinAlarmsPage: { template: '<div data-test="legacy-alarms" />' } }))
vi.mock('../api/alarms', () => ({ alarmsApi: api }))
vi.mock('@/shared/charts/ChartView.vue',()=>({default:{props:['option'],template:'<div class="trend-chart" />'}}))

import AlarmEventsPage from './AlarmEventsPage.vue'

const alarm = {
  id: 'c6494f7c-341b-4651-8ef6-0a3b4a92315c', buildingId: 'B1', category: 'EMISSION', type: 'QUOTA', objectId: 'O-1', objectName: '研发楼', spaceName: '3层', period: '2026-10',
  status: 'ACTIVE', severity: 'WARNING', description: '月度配额使用率达到预警条件。', firstAt: 1_800_000_000_000, lastAt: 1_800_000_000_100, recoveredAt: null, ackBy: null, ackAt: null,
  silencedUntil: null, ruleRevision: 4, triggerEvidence: JSON.stringify({ month: '2026-10', actual: '105', quota: '100', ratioPercent: '105.00', thresholdPercent: 85, unit: 'tCO2e', planVersion: 'internal-1' }),
  evidence: JSON.stringify({ month: '2026-10', actual: '108', quota: '100', ratioPercent: '108.00', thresholdPercent: 85, unit: 'tCO2e', batchId: 'internal-2' }), logs: [{ at: 1_800_000_000_100, actor: 8, action: 'ACKNOWLEDGED', note: '已复核' }],
}

describe('AlarmEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeState.path = '/operations/alarms/liveAlarms'
    routeState.query = { buildingId: 'B1', type: 'EMISSION' }
    session.menus = [{ path: '/operations/alarms/liveAlarms', status: 1, visible: 1 }]
    api.buildings.mockResolvedValue([{ buildingId: 'B1', buildingName: '研发楼' }, { buildingId: 'B2', buildingName: '办公楼' }])
    api.events.mockResolvedValue({ items: [alarm], total: 1, page: 1, size: 20 })
    api.summary.mockResolvedValue({ deviceAnomalies: 3, emissionWarnings: 1, deviceGroups: [], emissionGroups: [], updatedAt: Date.now(), period: '2026-10', deviceReason: null, emissionReason: null })
    api.event.mockResolvedValue(alarm)
    api.acknowledge.mockResolvedValue(undefined)
    api.note.mockResolvedValue(undefined)
    api.silence.mockResolvedValue(undefined)
  })

  it('starts with the deep-linked category, switches buildings and restores history authorization', async () => {
    const wrapper = mount(AlarmEventsPage)
    await flushPromises()
    expect(api.buildings).toHaveBeenCalledWith('live')
    expect(api.events).toHaveBeenCalledWith('B1', expect.objectContaining({ type: 'EMISSION', history: false }))
    expect(wrapper.text()).toContain('月度配额预警')
    expect(wrapper.findAll('.category-cards > button')).toHaveLength(2)
    expect(wrapper.text()).toContain('设备异常监控')
    expect(wrapper.text()).toContain('排放预警监控')

    await wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('change', 'B2')
    await flushPromises()
    expect(api.events).toHaveBeenLastCalledWith('B2', expect.objectContaining({ history: false }))

    const { useRoute } = await import('vue-router')
    useRoute().path = '/operations/alarms/historyAlarms'
    await flushPromises()
    expect(api.buildings).toHaveBeenLastCalledWith('history')
    expect(api.events).toHaveBeenLastCalledWith('B1', expect.objectContaining({ history: true }))
    wrapper.unmount()
  })

  it('renders evidence as translated fields without exposing internal JSON identifiers', async () => {
    const wrapper = mount(AlarmEventsPage)
    await flushPromises()
    await wrapper.get('.object-link').trigger('click')
    await flushPromises()
    expect(api.event).toHaveBeenCalledWith('B1', alarm.id)
    expect(wrapper.text()).toContain('评价月份')
    expect(wrapper.text()).toContain('配额使用率')
    expect(wrapper.text()).toContain('108.00%')
    expect(wrapper.text()).not.toContain('internal-1')
    expect(wrapper.text()).not.toContain('internal-2')
    expect(wrapper.text()).not.toContain('ACKNOWLEDGED')
    wrapper.unmount()
  })

  it('shows annual quota periods and server evaluated percentages without legacy empty fields',async()=>{
    const annual={...alarm,period:'2026-01/2026-10',evidence:JSON.stringify({template:'QUOTA_PROGRESS',value:600,reference:1000,unit:'tCO2e',evaluatedValue:60,evaluatedUnit:'%',threshold:50,recoveryThreshold:40})}
    api.events.mockResolvedValue({items:[annual],total:1,page:1,size:20})
    const wrapper=mount(AlarmEventsPage)
    await flushPromises()
    expect(wrapper.get('.phenomenon').text()).toContain('年度配额进度预警')
    expect(wrapper.get('.phenomenon').text()).toContain('60 %')
    wrapper.unmount()
  })

  it('submits a selected maintenance duration and displays raw trend source errors',async()=>{
    api.event.mockResolvedValue({...alarm,category:'DEVICE',type:'OFFLINE'})
    api.trend.mockResolvedValue([{pointId:'P1',pointName:'有功功率',unit:'kW',samples:[],unavailableReason:'原始趋势暂不可用'}])
    const wrapper=mount(AlarmEventsPage)
    await flushPromises()
    await wrapper.get('.object-link').trigger('click')
    await flushPromises()
    wrapper.get('.maintenance-note').findComponent({name:'ElInputNumber'}).vm.$emit('update:modelValue',120)
    wrapper.get('.action-card').findComponent({name:'ElInput'}).vm.$emit('update:modelValue','计划检修')
    await flushPromises()
    await wrapper.get('.maintenance-note button').trigger('click')
    await flushPromises()
    expect(api.silence).toHaveBeenCalledWith('B1',alarm.id,'计划检修',120)
    await wrapper.findAll('button').find(button=>button.text()==='查看趋势')!.trigger('click')
    await flushPromises()
    expect(api.trend).toHaveBeenCalled()
    expect(wrapper.text()).toContain('原始趋势暂不可用')
    wrapper.unmount()
  })

  it('offers manual close only after recovery and refreshes the persisted closed state',async()=>{
    api.event.mockResolvedValueOnce({...alarm,status:'RECOVERED'}).mockResolvedValue({...alarm,status:'CLOSED',closedAt:1800000002000})
    const wrapper=mount(AlarmEventsPage)
    await flushPromises()
    await wrapper.get('.object-link').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button=>button.text()==='确认关单')!.trigger('click')
    await flushPromises()
    expect(api.close).toHaveBeenCalledWith('B1',alarm.id,'')
    expect(wrapper.text()).toContain('关单时间')
    wrapper.unmount()
  })
})
