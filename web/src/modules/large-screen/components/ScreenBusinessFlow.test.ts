import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChartView from '@/shared/charts/ChartView.vue'
import ScreenBusinessFlow from './ScreenBusinessFlow.vue'

vi.mock('@/shared/charts/ChartView.vue', () => ({ default: { props: ['option', 'accessibleLabel'], template: '<div />' } }))

describe('配置化联合能碳流', () => {
  it('没有后端图时不生成用途、楼层或原型连线', () => {
    const wrapper = mount(ScreenBusinessFlow, { props: { mode: 'carbon' } })

    expect(wrapper.findComponent(ChartView).exists()).toBe(false)
    expect(wrapper.find('.business-flow-empty').text()).toContain('用途与楼层计量数据待对齐')
    expect(wrapper.find('.purpose-nodes').exists()).toBe(false)
    expect(wrapper.find('.floor-nodes').exists()).toBe(false)
    wrapper.unmount()
  })

  it('按后端维度标题、深度和节点顺序绘制一至三层分组到建筑合计', async () => {
    const data = {
      unit: 'tCO₂e', total: 99, columns: ['能源类型', '用途', '楼层', '建筑合计'],
      nodes: [
        { id: 'building', name: '建筑合计', depth: 3, order: 1 },
        { id: 'floor3', name: '3楼', depth: 2, order: 20 },
        { id: 'usage', name: '空调', depth: 1, order: 20 },
        { id: 'energy-late', name: '绿电', depth: 0, order: 20, value: 8 },
        { id: 'energy', name: '电力', depth: 0, order: 10, value: 4 },
      ],
      links: [{ source: 'energy-late', target: 'usage', value: 8 }, { source: 'energy', target: 'usage', value: 4 }, { source: 'usage', target: 'floor3', value: 12 }, { source: 'floor3', target: 'building', value: 12 }],
    }
    const wrapper = mount(ScreenBusinessFlow, { props: { mode: 'carbon', data } })
    const chart = wrapper.findComponent(ChartView)
    const option = chart.props('option')

    expect(wrapper.findAll('.business-flow-head span').map(item => item.text())).toEqual(data.columns)
    expect(option.series[0].data.map((node: { name: string }) => node.name)).toEqual(['energy', 'energy-late', 'usage', 'floor3', 'building'])
    expect(option.series[0].links.map((link: { source: string; target: string; value: number }) => ({ source: link.source, target: link.target, value: link.value }))).toEqual(data.links)
    expect(option.series[0].data.find((node: { name: string }) => node.name === 'building').label.formatter).toContain('{value|99.00}')
    expect(chart.props('accessibleLabel')).toContain('99 tCO₂e')
    const tooltip = option.tooltip as { formatter: (params: unknown) => string; renderMode: string }
    expect(tooltip.renderMode).toBe('richText')
    expect(tooltip.formatter({ dataType: 'edge', data: data.links[0] })).toBe('绿电 → 空调\n8 tCO₂e')
    expect(tooltip.formatter({ dataType: 'node', data: { name: 'building' } })).toBe('建筑合计\n99 tCO₂e')
    expect(wrapper.get('.flow-pending').text()).toContain('悬停')
    await wrapper.setProps({ mode: 'energy' })
    expect(wrapper.findComponent(ChartView).exists()).toBe(false)
    expect(wrapper.text()).toContain('流图范围或单位不匹配')
    expect(data.links[0]).toEqual({ source: 'energy-late', target: 'usage', value: 8 })
    wrapper.unmount()
  })

  it('用 richText 工具提示和受限标签文字隔离后端节点名', () => {
    const data = {
      unit: 'tCO₂e', total: 12, columns: ['能源', '建筑'],
      nodes: [{ id: 'source', name: '{恶意|格式}', depth: 0, order: 1 }, { id: 'total', name: '<建筑>', depth: 1, order: 1 }],
      links: [{ source: 'source', target: 'total', value: 12 }],
    }
    const wrapper = mount(ScreenBusinessFlow, { props: { mode: 'carbon', data } })
    const series = wrapper.findComponent(ChartView).props('option').series[0]

    expect(series.data[0].label.formatter).toBe('（恶意｜格式）')
    expect(series.data[1].label.formatter).toContain('{name|<建筑>}')
    expect(series.links[0].lineStyle.color).toBe(series.data[0].itemStyle.color)
    expect(series).toMatchObject({ nodeWidth: 10, nodeGap: 22, draggable: false, layoutIterations: 0, lineStyle: { opacity: 0.52, curveness: 0.5 }, emphasis: { focus: 'adjacency' } })
    wrapper.unmount()
  })
})
