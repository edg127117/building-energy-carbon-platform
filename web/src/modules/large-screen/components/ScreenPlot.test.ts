import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ScreenPlot from './ScreenPlot.vue'
import ChartView from '@/shared/charts/ChartView.vue'
vi.mock('@/shared/charts/ChartView.vue', () => ({ default: { props: ['option', 'accessibleLabel'], template: '<div />' } }))
describe('screen chart rendering', () => {
  it('preserves zero, missing points and the independent temperature axis without aggregation', () => {
    const wrapper = mount(ScreenPlot, { props: { kind: 'temperature', label: '同周期数据', valueAxisUnit: 'kgCO₂e', periods: ['00:00', '01:00', '02:00'], series: [{ name: '排放', values: [0, null, 0.2] }, { name: '温度', values: [22, 23, null], axis: 1 }] } })
    const option = wrapper.findComponent(ChartView).props('option')
    expect(option).toMatchObject({ yAxis: [{ name: 'kgCO₂e' }, { name: '°C' }], series: [{ data: [0, null, 0.2], connectNulls: false }, { yAxisIndex: 1 }] })
    expect(wrapper.find('.plot-state').exists()).toBe(false)
    wrapper.unmount()
  })
  it('renders stacked comparisons and marks prediction as a separate dashed series', () => {
    const comparison = mount(ScreenPlot, { props: { kind: 'comparison', label: '对比', series: [{ name: '电力', values: [1, 0, 2] }] } })
    const option = comparison.findComponent(ChartView).props('option')
    expect(option).toMatchObject({ series: [{ type: 'bar', stack: 'carbon' }] })
    comparison.unmount()
    const prediction = mount(ScreenPlot, { props: { kind: 'prediction', label: '预测', series: [{ name: '预测', values: [0.1, 0.2], predicted: true }] } })
    expect(prediction.findComponent(ChartView).props('option')).toMatchObject({ series: [{ lineStyle: { type: 'dashed' } }] })
    prediction.unmount()
  })
})
