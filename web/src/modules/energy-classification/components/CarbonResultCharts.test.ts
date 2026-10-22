import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { CarbonClassificationReport } from '../api/carbon-results'
import CarbonResultCharts from './CarbonResultCharts.vue'

vi.mock('@/shared/charts/ChartView.vue', () => ({ default: { name: 'TestChartView', props: ['option', 'empty'], template: '<div class="chart" />' } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { template: '<div><slot /></div>' }, ElSelect: { template: '<div><slot /></div>' }, ElOption: { template: '<span />' },
}))

const report = (): CarbonClassificationReport => ({ buildingId: 'B', batchId: 'C', status: 'COMPLETED_COMPLETE', unit: 'KG_CO2E', totalKgCo2e: '100', classificationComplete: true, nodes: [], periods: [
  { startInclusive: '2026-01-01T00:00:00Z', scopeType: 'SCOPE_1', emissionKgCo2e: '40' },
  { startInclusive: '2026-02-01T00:00:00Z', scopeType: 'SCOPE_2', emissionKgCo2e: '60' },
], excludedSources: [], warnings: [], flows: [] })

describe('冻结碳结果图', () => {
  it('缺少某范围月份时保留空值，不由前端补零', () => {
    const wrapper = mount(CarbonResultCharts, { props: { report: report() } })
    // 从绘图边界取值，验证只有服务端提供的两个数，缺项保持null。
    const model = wrapper.findComponent({ name: 'TestChartView' }).props('option')
    expect(model.series.map((series: { data: unknown[] }) => series.data)).toEqual([[40, null], [null, 60]])
    wrapper.unmount()
  })

  it('交叉分类只展示服务端不可加总说明，不渲染流图', () => {
    const source = report()
    source.flows = [{ schemeCode: 'REGION', available: false, unavailableReason: '交叉分类不可相加', nodes: [], links: [] }]
    const wrapper = mount(CarbonResultCharts, { props: { report: source } })
    expect(wrapper.find('[role="alert"]').text()).toBe('交叉分类不可相加')
    expect(wrapper.findAll('.chart')).toHaveLength(1)
    wrapper.unmount()
  })

  it('流图提示显示可读名称和服务端原始精度，不泄露内部绘图ID', () => {
    const source = report()
    const raw = '0.1234567890123456789'
    source.flows = [{ schemeCode: 'USAGE', available: true, unavailableReason: null, nodes: [{ id: 'INTERNAL_TOTAL', type: 'TOTAL', code: 'TOTAL', name: 'TOTAL' }, { id: 'INTERNAL_NODE', type: 'CLASSIFICATION', code: 'N', name: '空调' }], links: [{ source: 'INTERNAL_TOTAL', target: 'INTERNAL_NODE', value: raw }] }]
    const wrapper = mount(CarbonResultCharts, { props: { report: source } })
    const option = wrapper.findAllComponents({ name: 'TestChartView' })[1]!.props('option')
    const text = option.tooltip.formatter({ name: '', data: option.series[0].links[0] })
    expect(text).toContain(raw)
    expect(text).toContain('空调')
    expect(text).not.toContain('INTERNAL_')
    wrapper.unmount()
  })
})
