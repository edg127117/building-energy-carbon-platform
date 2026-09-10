import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SceneScreenLayout from './SceneScreenLayout.vue'

describe('scene information layout', () => {
  it('collapses sides independently and retains the mounted scene and panel state', async () => {
    const wrapper = mount(SceneScreenLayout, { attachTo: document.body, slots: { scene: '<div class="scene-test" />', top: 'summary', left: '<input value="retained" />', right: 'right' } })
    const scene = wrapper.get('.scene-test').element
    await wrapper.get('[aria-label="收起左侧面板"]').trigger('click')
    expect(wrapper.get('.left-anchor').classes()).toContain('collapsed')
    expect(wrapper.get('.right-anchor').classes()).not.toContain('collapsed')
    expect(wrapper.get('[aria-label="展开左侧面板"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.left-anchor aside').isVisible()).toBe(false)
    await wrapper.get('[aria-label="收起右侧面板"]').trigger('click')
    await wrapper.get('[aria-label="展开左侧面板"]').trigger('click')
    expect(wrapper.get('.scene-test').element).toBe(scene)
    expect(wrapper.get('input').element.value).toBe('retained')
    expect(wrapper.get('.left-anchor aside').isVisible()).toBe(true)
    expect(wrapper.get('.right-anchor').classes()).toContain('collapsed')
    wrapper.unmount()
  })
  it('does not reserve panels or a summary when no corresponding slots exist', () => {
    const wrapper = mount(SceneScreenLayout, { slots: { scene: 'scene' } })
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.find('.scene-summary').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('has-summary')
    wrapper.unmount()
  })
})
