import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ summary: vi.fn() }))
const push = vi.hoisted(() => vi.fn())
type TestMenu = { path: string | null; menuType: string; status: number; visible: number; children?: TestMenu[] }
const session = vi.hoisted(() => ({ menus: [] as TestMenu[] }))
vi.mock('../api/alarms', () => ({ alarmsApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => session }))

import ScreenAlarmSummary from './ScreenAlarmSummary.vue'

const payload = (deviceAnomalies: number) => ({ deviceAnomalies, emissionWarnings: 1, deviceGroups: [{ type: 'OFFLINE', count: deviceAnomalies }], emissionGroups: [{ type: 'QUOTA', count: 1 }], updatedAt: 1_800_000_000_000, period: '今日', deviceReason: null, emissionReason: null })

describe('ScreenAlarmSummary', () => {
  it('resets the five-minute idle timeout on pointer interaction and closes afterwards', async () => {
    vi.useFakeTimers()
    api.summary.mockResolvedValue(payload(3))
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    try {
      await flushPromises()
      await wrapper.get('.summary-card.device').trigger('click')
      vi.advanceTimersByTime(299_000)
      await wrapper.get('.summary-dialog').trigger('pointerdown')
      vi.advanceTimersByTime(299_000)
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
      vi.advanceTimersByTime(1_001)
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })
  beforeEach(() => {
    api.summary.mockReset()
    push.mockReset()
    session.menus = [{ path: '/operations', menuType: 'M', status: 1, visible: 1, children: [{ path: '/operations/alarms', menuType: 'M', status: 1, visible: 1, children: [{ path: '/operations/alarms/liveAlarms', menuType: 'C', status: 1, visible: 1 }] }] }]
  })

  it('shows the two backend totals and opens the alarm breakdown dialog', async () => {
    api.summary.mockResolvedValue(payload(3))
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    expect(api.summary).toHaveBeenCalledWith('B1')
    expect(wrapper.text()).toContain('3')
    expect(wrapper.text()).toContain('1')
    await wrapper.get('.summary-card.device').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('通信超时')
    await wrapper.get('[role="dialog"] footer button').trigger('click')
    expect(push).toHaveBeenCalledWith({ path: '/operations/alarms/liveAlarms', query: { buildingId: 'B1', type: 'DEVICE' } })
    wrapper.unmount()
  })

  it('clears the prior building summary while the next request is pending', async () => {
    let resolveNext!: (value: ReturnType<typeof payload>) => void
    api.summary.mockResolvedValueOnce(payload(8)).mockImplementationOnce(() => new Promise(resolve => { resolveNext = resolve }))
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await wrapper.setProps({ buildingId: 'B2' })
    expect(wrapper.text()).not.toContain('8')
    resolveNext(payload(2))
    await flushPromises()
    expect(wrapper.text()).toContain('2')
    wrapper.unmount()
  })

  it('keeps null totals unavailable and hides the events link without its menu', async () => {
    session.menus = []
    api.summary.mockResolvedValue({ ...payload(0), deviceAnomalies: null, deviceReason: '当前数据源未接入' })
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await wrapper.get('.summary-card.device').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('当前数据源未接入')
    expect(wrapper.get('[role="dialog"]').text()).toContain('—')
    expect(wrapper.find('[role="dialog"] footer button').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders an explicit empty state for a valid zero total', async () => {
    api.summary.mockResolvedValue(payload(0))
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await wrapper.get('.summary-card.device').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('当前异常设备总数：')
    expect(wrapper.get('[role="dialog"]').text()).toContain('当前无设备异常')
    wrapper.unmount()
  })

  it('requires a visible alarm leaf inside enabled visible ancestors', async () => {
    api.summary.mockResolvedValue(payload(1))
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await wrapper.get('.summary-card.device').trigger('click')
    expect(wrapper.find('.events-link').exists()).toBe(true)
    wrapper.unmount()

    session.menus[0].status = 0
    const denied = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await denied.get('.summary-card.device').trigger('click')
    expect(denied.find('.events-link').exists()).toBe(false)
    denied.unmount()
  })
  it('shows the four prototype groups, including real zero counts, and switches categories', async () => {
    api.summary.mockResolvedValue({ ...payload(3), deviceGroups: [
      { type: 'OFFLINE', count: 2 }, { type: 'QUALITY', count: 1 }, { type: 'VALUE', count: 0 }, { type: 'FAULT', count: 0 },
    ] })
    const wrapper = mount(ScreenAlarmSummary, { props: { buildingId: 'B1' } })
    await flushPromises()
    await wrapper.get('.summary-card.device').trigger('click')
    expect(wrapper.findAll('.summary-body li')).toHaveLength(4)
    expect(wrapper.get('.quality-group').text()).toContain('数据质量不达标')
    expect(wrapper.get('.value-group').text()).toContain('0 台')
    await wrapper.findAll('.summary-tabs button')[1].trigger('click')
    expect(wrapper.findAll('.summary-body li')).toHaveLength(1)
    expect(wrapper.get('.summary-dialog').text()).toContain('配额进度预警')
    wrapper.unmount()
  })

})
