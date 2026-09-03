import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusCard from './StatusCard.vue'

describe('screen status presentation', () => {
  it('keeps an unknown status and freshness unknown even with an update timestamp', () => {
    const wrapper = mount(StatusCard, { props: { title: '测试状态', updatedAt: '2026-09-03T00:00:00Z', timeZone: 'UTC' } })
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('数据更新时间：')
    expect(wrapper.text()).not.toContain('数据可用')
    expect(wrapper.text()).not.toContain('数据已失效')
    wrapper.unmount()
  })
  it('uses supplied status and freshness text without deriving domain rules', () => {
    const wrapper = mount(StatusCard, { props: { title: '测试状态', statusText: '等待复核', tone: 'warning', updatedAt: 0, stale: true, timeZone: 'UTC' } })
    expect(wrapper.text()).toContain('等待复核')
    expect(wrapper.text()).toContain('数据已失效')
    wrapper.unmount()
  })
})
