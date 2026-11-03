import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ScreenDeviceSummary from './ScreenDeviceSummary.vue'

vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const messages: Record<string, string> = {
      'largeScreen.deviceSummaryLabel': '空调设备状态汇总',
      'largeScreen.deviceSummaryScope': '空调系统',
      'largeScreen.deviceSummaryLive': '动态更新',
      'largeScreen.deviceSummaryRefresh': '刷新于 {time}',
      'largeScreen.deviceSummarySwitch': '开关状态',
      'largeScreen.deviceSummaryModes': '运行模式',
      'largeScreen.deviceSummarySpeeds': '风速档位',
      'largeScreen.deviceSummaryOn': '开机',
      'largeScreen.deviceSummaryOff': '关机',
      'largeScreen.deviceSummaryUnknown': '未知',
      'largeScreen.deviceSummaryFan': '送风',
      'largeScreen.deviceSummaryCooling': '制冷',
      'largeScreen.deviceSummaryHeating': '制热',
      'largeScreen.deviceSummaryCountWithTotal': '{count} / {total} 台',
      'largeScreen.deviceSummaryStatusCount': '{label}：{count}',
      'largeScreen.deviceSummaryNumberMissing': '—',
      'largeScreen.deviceSummaryMissing': '—',
    }
    return (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('screen device summary', () => {
  it('preserves zero, keeps unknown distinct from off, and uses only supplied mode and speed entries', () => {
    const wrapper = mount(ScreenDeviceSummary, {
      props: {
        data: {
          total: 0,
          on: 0,
          off: null,
          unknown: 3,
          modes: [
            { key: 'fan', label: '送风', count: 0 },
            { key: 'cooling', label: '制冷', count: null },
            { key: 'heating', label: '制热', count: 1 },
          ],
          speeds: [
            { key: 'auto', label: '自动', count: 0 },
            { key: 'quiet', label: '静音', count: null },
          ],
        },
      },
    })

    expect(wrapper.get('[data-testid="device-on"]').text()).toContain('0 / 0 台')
    expect(wrapper.get('[data-testid="device-off"]').text()).toContain('— / 0 台')
    expect(wrapper.get('[data-testid="device-unknown"]').text()).toContain('3 / 0 台')
    expect(wrapper.get('[data-testid="device-unknown"]').classes()).toContain('switch-unknown')
    expect(wrapper.get('[data-testid="mode-fan"]').classes()).toContain('mode-fan')
    expect(wrapper.get('[data-testid="mode-cooling"]').classes()).toContain('mode-cooling')
    expect(wrapper.get('[data-testid="mode-heating"]').classes()).toContain('mode-heating')
    expect(wrapper.findAll('.device-summary-entry.speed')).toHaveLength(2)
    expect(wrapper.text()).toContain('自动')
    expect(wrapper.text()).toContain('静音')
    wrapper.unmount()
  })

  it('retains the three summary zones without treating unavailable input as zero', () => {
    const wrapper = mount(ScreenDeviceSummary)

    expect(wrapper.get('[data-testid="device-on"]').text()).toContain('— / — 台')
    expect(wrapper.get('[data-testid="device-off"]').text()).toContain('— / — 台')
    expect(wrapper.get('[data-testid="device-unknown"]').text()).toContain('— / — 台')
    expect(wrapper.findAll('.device-summary-zone')).toHaveLength(3)
    expect(wrapper.findAll('.device-summary-missing')).toHaveLength(2)
    wrapper.unmount()
  })

  it('preserves a real count of zero when the total is unavailable', () => {
    const wrapper = mount(ScreenDeviceSummary, {
      props: {
        data: {
          total: null,
          on: 0,
          off: null,
          unknown: null,
          modes: [],
          speeds: [],
        },
      },
    })

    expect(wrapper.get('[data-testid="device-on"]').text()).toContain('0 / — 台')
    expect(wrapper.get('[data-testid="device-off"]').text()).toContain('— / — 台')
    wrapper.unmount()
  })
  it('shows the air-conditioning badge, refresh clock, and motion pause state', async () => {
    const wrapper = mount(ScreenDeviceSummary, { props: { updatedAt: Date.UTC(2026, 9, 28, 2, 20, 30), animate: false } })
    expect(wrapper.get('.device-summary-scope').text()).toBe('空调系统')
    expect(wrapper.get('.device-summary-live').text()).toContain('动态更新')
    expect(wrapper.get('.device-summary-live time').text()).toBe('10:20:30')
    expect(wrapper.classes()).toContain('is-paused')
    await wrapper.setProps({ updatedAt: undefined })
    expect(wrapper.find('.device-summary-live').exists()).toBe(false)
    wrapper.unmount()
  })

  it('updates counts in the existing text node without remounting the reading or row', async () => {
    const data = { total: 2, on: 1, off: 1, unknown: 0, modes: [], speeds: [] }
    const wrapper = mount(ScreenDeviceSummary, { props: { data } })
    const row = wrapper.get('[data-testid="device-on"]').element
    const reading = wrapper.get('[data-testid="device-on"] small').element
    await wrapper.setProps({ data: { ...data, on: 2, off: 0 } })
    expect(wrapper.get('[data-testid="device-on"]').element).toBe(row)
    expect(wrapper.get('[data-testid="device-on"] small').element).toBe(reading)
    expect(reading.textContent).toBe('2 / 2 台')
    wrapper.unmount()
  })

})
