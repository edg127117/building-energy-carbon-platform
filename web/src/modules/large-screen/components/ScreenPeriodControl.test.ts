import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ScreenPeriodControl from './ScreenPeriodControl.vue'
const global = { stubs: { ElPopover: defineComponent({ emits: ['show'], template: '<div><div @click="$emit(\'show\')"><slot name="reference" /></div><slot /></div>' }) } }
describe('year/month period drafts', () => {
  it('only commits on apply and restores the selected period after cancellation', async () => {
    const wrapper = mount(ScreenPeriodControl, { props: { modelValue: { kind: 'year', value: '2026' }, label: '统计期间' }, global })
    await wrapper.get('.period-trigger').trigger('click')
    await wrapper.get('input').setValue('2025')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.get('.period-actions button[type=button]').trigger('click')
    await wrapper.get('.period-trigger').trigger('click')
    expect(wrapper.get('input').element.value).toBe('2026')
    await wrapper.get('.period-kinds button:nth-child(2)').trigger('click')
    expect(wrapper.get('input').attributes('type')).toBe('month')
    await wrapper.get('input').setValue('2026-08')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([{ kind: 'month', value: '2026-08' }])
    wrapper.unmount()
  })
  it('provides only year/month and rejects out-of-range years', async () => {
    const wrapper = mount(ScreenPeriodControl, { props: { modelValue: { kind: 'year', value: '2026' }, label: '统计期间' }, global })
    await wrapper.get('.period-trigger').trigger('click')
    expect(wrapper.findAll('.period-kinds button').map(button => button.text())).toEqual(['年', '月'])
    await wrapper.get('input').setValue('1900')
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
})
