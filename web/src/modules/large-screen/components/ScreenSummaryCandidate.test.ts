import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ScreenSummaryCandidate from './ScreenSummaryCandidate.vue'
import { prototypeAlarms, prototypeDevices, prototypeRuntimeRanking } from '../models/screen-prototype'

vi.mock('@/locales', async () => {
  const { default: messages } = await import('../locales/scene')
  return { t: (key: string, params: Record<string, string | number> = {}) => {
    const message = messages[key.replace('largeScreen.', '') as keyof typeof messages]
    return message.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  } }
})

const base = { alarms: prototypeAlarms, devices: prototypeDevices, updatedAt: Date.UTC(2026, 9, 28, 2, 20, 30) }

describe('summary card design candidates', () => {
  it.each(['a', 'b', 'c'] as const)('%s保留两种报警、空调范围和刷新时间', (variant) => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { ...base, variant } })
    expect(wrapper.get('[data-test="candidate-alarm-device"]').text()).toContain('设备异常3台')
    expect(wrapper.get('[data-test="candidate-alarm-emission"]').text()).toContain('排放预警2条')
    expect(wrapper.get('.system-label').text()).toBe('空调系统')
    expect(wrapper.get('.live-stamp time').text()).toBe('10:20:30')
    expect(wrapper.get('[data-test="candidate-switch-on"]').text()).toContain('25/ 40 台')
    wrapper.unmount()
  })
  it('A默认显示全部十项，分组收起只改变可见性且可恢复', async () => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { ...base, variant: 'a' }, attachTo: document.body })
    expect(wrapper.findAll('.status-reading')).toHaveLength(10)
    const reading = wrapper.get('[data-test="candidate-speed-auto"]').element
    await wrapper.get('[aria-label="收起风速档位"]').trigger('click')
    expect(wrapper.get('[data-test="candidate-speed-auto"]').isVisible()).toBe(false)
    expect(wrapper.get('[data-test="candidate-mode-cool"]').isVisible()).toBe(true)
    await wrapper.get('[aria-label="展开风速档位"]').trigger('click')
    expect(wrapper.get('[data-test="candidate-speed-auto"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-test="candidate-speed-auto"]').element).toBe(reading)
    wrapper.unmount()
  })
  it('B在运行状态和月运行时间排名间切换，不改变原状态数量', async () => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { ...base, variant: 'b', runtimeRanking: prototypeRuntimeRanking } })
    expect(wrapper.findAll('.status-reading')).toHaveLength(10)
    await wrapper.get('.group-filter button:last-child').trigger('click')
    expect(wrapper.findAll('.ranking-card')).toHaveLength(5)
    expect(wrapper.get('[data-test="candidate-rank-1"]').text()).toContain('612')
    expect(wrapper.find('[data-test="candidate-switch-on"]').exists()).toBe(false)
    await wrapper.get('.group-filter button:first-child').trigger('click')
    expect(wrapper.findAll('.status-reading')).toHaveLength(10)
    wrapper.unmount()
  })
  it('C三个分组均可切换，切换版本恢复对应默认阅读方式', async () => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { ...base, variant: 'c' } })
    expect(wrapper.findAll('.status-reading')).toHaveLength(3)
    await wrapper.findAll('.group-filter button')[1].trigger('click')
    expect(wrapper.get('[data-test="candidate-mode-heat"]').text()).toContain('5/ 40 台')
    await wrapper.findAll('.group-filter button')[2].trigger('click')
    expect(wrapper.findAll('.status-reading')).toHaveLength(4)
    expect(wrapper.get('[data-test="candidate-speed-auto"]').text()).toContain('2/ 40 台')
    await wrapper.setProps({ variant: 'b' })
    expect(wrapper.findAll('.status-reading')).toHaveLength(10)
    wrapper.unmount()
  })
  it('两个卡片可以独立收起展开，刷新数据不重建数字节点', async () => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { ...base, variant: 'a' } })
    await wrapper.get('[aria-label="收起报警信息"]').trigger('click')
    expect(wrapper.get('.alarm-readings').isVisible()).toBe(false)
    expect(wrapper.get('.device-content').isVisible()).toBe(true)
    await wrapper.get('[aria-label="展开报警信息"]').trigger('click')
    const reading = wrapper.get('[data-test="candidate-switch-on"] b').element
    await wrapper.setProps({ devices: { ...prototypeDevices, on: 27, off: 10 }, updatedAt: Date.UTC(2026, 9, 28, 2, 20, 38) })
    expect(wrapper.get('[data-test="candidate-switch-on"] b').element).toBe(reading)
    expect(reading.textContent).toBe('27')
    expect(wrapper.get('.live-stamp time').text()).toBe('10:20:38')
    wrapper.unmount()
  })
  it('缺少总数时保留真实零值，不由其他数量推算或补零', () => {
    const wrapper = mount(ScreenSummaryCandidate, { props: { variant: 'a', devices: { ...prototypeDevices, total: null, on: 0, off: null }, alarms: { deviceAnomalies: 0, emissionWarnings: null } } })
    expect(wrapper.get('[data-test="candidate-switch-on"]').text()).toContain('0/ — 台')
    expect(wrapper.get('[data-test="candidate-switch-off"]').text()).toContain('—/ — 台')
    expect(wrapper.get('[data-test="candidate-alarm-device"] b').text()).toBe('0')
    expect(wrapper.get('[data-test="candidate-alarm-emission"] b').text()).toBe('—')
    wrapper.unmount()
  })
})
