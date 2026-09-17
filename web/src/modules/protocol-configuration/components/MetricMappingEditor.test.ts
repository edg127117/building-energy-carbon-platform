import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ElSelect } from '@/shared/ui'
import MetricMappingEditor from './MetricMappingEditor.vue'
import type { ProductPointTemplate } from '@/modules/device-onboarding/public'

const point: ProductPointTemplate = { metricCode: 'VOLTAGE', pointNameTemplate: '电压', suffixCode: 'U', unit: 'V', minValue: null, maxValue: null, forCalc: false, required: true, sortOrder: 1, enabled: true }
describe('测点优先映射', () => {
  it.each([7, 11])('展示 %i 项模板并保留所有待配置测点，不猜单位', async count => {
    const points = Array.from({ length: count }, (_, index) => ({ ...point, metricCode: `POINT_${index}`, pointNameTemplate: `测点 ${index}` }))
    const wrapper = mount(MetricMappingEditor, { props: { points, mappings: [], fields: [{ path: '/value', type: 'NUMBER', value: '220' }] } })
    expect(wrapper.findAll('.mapping-row')).toHaveLength(count)
    wrapper.findComponent(ElSelect).vm.$emit('change', '/value')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:mappings')?.[0]?.[0]).toEqual([expect.objectContaining({ metricCode: 'POINT_0', sourceUnit: '', targetUnit: 'V', required: true })])
    wrapper.unmount()
  })
  it('重选来源保留已确认倍率和禁用状态，清空才移除', async () => {
    const mapping = { metricCode: 'VOLTAGE', sourcePath: '/old', sourceUnit: 'mV', targetUnit: 'V', scale: '0.001', offset: '0', enabled: false, required: true, sortOrder: 1 }
    const wrapper = mount(MetricMappingEditor, { props: { points: [point], mappings: [mapping], fields: [] } })
    expect(wrapper.text()).toContain('映射已停用')
    wrapper.findComponent(ElSelect).vm.$emit('change', '/new')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:mappings')?.[0]?.[0]).toEqual([{ ...mapping, sourcePath: '/new' }])
    wrapper.findComponent(ElSelect).vm.$emit('change', '')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:mappings')?.[1]?.[0]).toEqual([])
    wrapper.unmount()
  })
})
