import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ScreenAlarmSummary from './ScreenAlarmSummary.vue'

vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const messages: Record<string, string> = {
      'largeScreen.alarmSummaryLabel': '报警信息汇总',
      'largeScreen.alarmSummaryDeviceAnomalies': '设备异常',
      'largeScreen.alarmSummaryEmissionWarnings': '排放预警',
      'largeScreen.alarmSummaryDeviceUnit': '台',
      'largeScreen.alarmSummaryWarningUnit': '条',
      'largeScreen.alarmSummaryCount': '{label}：{count}{unit}',
      'largeScreen.alarmSummaryMissing': '—',
    }
    return (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('screen alarm summary', () => {
  it('preserves a real zero and renders a missing warning count as unavailable', () => {
    const wrapper = mount(ScreenAlarmSummary, {
      props: { data: { deviceAnomalies: 0, emissionWarnings: null } },
    })

    expect(wrapper.get('[data-testid="alarm-device"]').text()).toContain('0台')
    expect(wrapper.get('[data-testid="alarm-emission"]').text()).toContain('—条')
    expect(wrapper.get('[data-testid="alarm-device"]').classes()).toContain('device-anomaly')
    expect(wrapper.get('[data-testid="alarm-emission"]').classes()).toContain('emission-warning')
    wrapper.unmount()
  })

  it('keeps two explicit summary blocks without tabs, event lists, or drill-down links', () => {
    const wrapper = mount(ScreenAlarmSummary)

    expect(wrapper.findAll('.alarm-summary-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('—')
    expect(wrapper.findAll('button, a, ul, ol')).toHaveLength(0)
    wrapper.unmount()
  })
})
