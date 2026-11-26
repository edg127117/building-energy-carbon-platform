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

  it('根据试解析错误高亮定位到对应测点行与原始单位字段', () => {
    const mapping = { metricCode: 'VOLTAGE', sourcePath: '/v', sourceUnit: '', targetUnit: 'V', scale: '1', offset: '0', enabled: true, required: true, sortOrder: 1 }
    const wrapper = mount(MetricMappingEditor, {
      props: { points: [point], mappings: [mapping], fields: [], highlightedMetric: 'VOLTAGE', highlightedField: 'sourceUnit' },
    })
    expect(wrapper.find('#metric-VOLTAGE').classes()).toContain('mapping-row--highlighted')
    expect(wrapper.findAll('.field-highlight')).toHaveLength(1)
    wrapper.unmount()
  })

  it('打开原始单位、倍率和偏移为 null 的合法半成品草稿时正常渲染且可继续编辑', () => {
    const partial = { metricCode: 'VOLTAGE', sourcePath: '/v', sourceUnit: null, targetUnit: 'V', scale: null, offset: null, enabled: true, required: true, sortOrder: 1 } as unknown as {
      metricCode: string; sourcePath: string; sourceUnit: string; targetUnit: string; scale: string; offset: string; enabled: boolean; required: boolean; sortOrder: number
    }
    const wrapper = mount(MetricMappingEditor, {
      props: { points: [point], mappings: [partial], fields: [{ path: '/v', type: 'NUMBER', value: '220' }] },
    })
    expect(wrapper.text()).toContain('请确认原始单位')
    expect(wrapper.find('input[aria-label="电压 原始单位"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
