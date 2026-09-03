import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import StateBoundary from './StateBoundary.vue'
describe('local state boundary', () => {
  it.each(['loading', 'empty', 'error', 'forbidden', 'not-found'] as const)('hides content for %s', state => {
    const wrapper = mount(StateBoundary, { props: { state }, slots: { default: '<span data-business>content</span>' } })
    expect(wrapper.find('[data-business]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('undefined')
    wrapper.unmount()
  })
  it('isolates a crashing child and recovers on page change', async () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    let fail = true
    const Child = defineComponent({ setup: () => () => { if (fail) throw new Error('internal failure'); return h('div', { 'data-ok': '' }) } })
    const wrapper = mount(StateBoundary, { props: { resetKey: 'first' }, slots: { default: Child } })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('页面加载失败')
    expect(wrapper.text()).not.toContain('internal failure')
    fail = false
    await wrapper.setProps({ resetKey: 'next' })
    expect(wrapper.find('[data-ok]').exists()).toBe(true)
    wrapper.unmount()
    spy.mockRestore()
  })
})
