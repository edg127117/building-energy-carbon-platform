import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChartView from '@/shared/charts/ChartView.vue'
import ScreenMirrorChart from './ScreenMirrorChart.vue'

vi.mock('@/shared/charts/ChartView.vue', () => ({
  default: { name: 'ChartView', props: ['option', 'accessibleLabel'], template: '<div data-test="chart-view" />' },
}))
vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const value = ({
      'largeScreen.periodMonthTick': '{value}月',
      'largeScreen.periodDayTick': '{value}日',
      'largeScreen.monthShort': '月',
      'largeScreen.dayShort': '日',
      'largeScreen.missing': '—',
      'largeScreen.mirrorDynamicEmissions': '动态碳排放量',
      'largeScreen.mirrorStaticEmissions': '静态碳排放量',
      'largeScreen.mirrorDynamicFactors': '动态碳排放因子',
      'largeScreen.mirrorStaticFactors': '静态碳排放因子',
      'largeScreen.mirrorChartLabel': '镜像图表',
      'largeScreen.mirrorTooltipItem': '{name}：{value} {unit}',
      'largeScreen.mirrorEmpty': '暂无动态与静态碳排放数据',
    })[key] ?? key
    return value.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('ScreenMirrorChart', () => {
  it('静态结果尚在准备时明确显示加载状态', () => {
    const wrapper = mount(ScreenMirrorChart, { props: { period: { kind: 'month', value: '2026-01' }, data: null, loading: true } })
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="mirror-empty"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('把静态序列仅映射为负坐标，仍以正值展示轴标签和悬停读数', () => {
    const wrapper = mount(ScreenMirrorChart, {
      props: {
        period: { kind: 'year', value: '2026' },
        data: {
          dynamicEmissions: [5, 0, null],
          staticEmissions: [4, 2, null],
          dynamicFactors: [0.45, 0, null],
          staticFactors: [0.5, 0.6, null],
          emissionUnit: 'tCO₂e',
          factorUnit: 'kgCO₂e/kWh',
        },
      },
    })

    const option = wrapper.findComponent(ChartView).props('option') as {
      xAxis: { axisLabel: { interval: number } }
      yAxis: Array<{ name: string; axisLabel: { formatter: (value: number) => string } }>
      series: Array<{ id: string; type: string; data: Array<number | null>; yAxisIndex: number; smooth?: boolean; showSymbol?: boolean; connectNulls?: boolean }>
      tooltip: { formatter: (params: unknown) => string }
    }
    expect(option.xAxis.axisLabel.interval).toBe(0)
    expect(option.yAxis.map(axis => axis.name)).toEqual(['tCO₂e', 'kgCO₂e/kWh'])
    expect(option.yAxis[0].axisLabel.formatter(-4)).toBe('4.00')
    expect(option.yAxis[1].axisLabel.formatter(-0.5)).toBe('0.50')
    expect(option.series.map(series => ({
      id: series.id,
      type: series.type,
      yAxisIndex: series.yAxisIndex,
      smooth: series.smooth,
      showSymbol: series.showSymbol,
      connectNulls: series.connectNulls,
    }))).toEqual([
      { id: 'dynamic-emissions', type: 'bar', yAxisIndex: 0, smooth: undefined, showSymbol: undefined, connectNulls: undefined },
      { id: 'static-emissions', type: 'bar', yAxisIndex: 0, smooth: undefined, showSymbol: undefined, connectNulls: undefined },
      { id: 'dynamic-factors', type: 'line', yAxisIndex: 1, smooth: false, showSymbol: true, connectNulls: false },
      { id: 'static-factors', type: 'line', yAxisIndex: 1, smooth: false, showSymbol: true, connectNulls: false },
    ])
    expect(option.series.map(series => series.data.slice(0, 3))).toEqual([[5, 0, null], [-4, -2, null], [0.45, 0, null], [-0.5, -0.6, null]])
    expect(option.tooltip.formatter([{
      axisValueLabel: '1月', seriesId: 'static-emissions', seriesName: '静态碳排放量', data: -4,
    }])).toBe('1月\n静态碳排放量：4.00 tCO₂e')
    wrapper.unmount()
  })

  it('排放量、因子及十字量标固定两位，镜像提示保留实际正值', () => {
    const wrapper = mount(ScreenMirrorChart, { props: { period: { kind: 'year', value: '2026' }, data: null } })
    const option = wrapper.findComponent(ChartView).props('option') as {
      tooltip: { formatter: (params: unknown) => string }
      yAxis: Array<{ axisPointer: { label: { formatter: (params: { value: number }) => string } } }>
    }
    expect(option.tooltip.formatter([
      { axisValueLabel: '1月', seriesId: 'dynamic-emissions', seriesName: '动态碳排放量', value: 6.5 },
      { seriesId: 'static-emissions', seriesName: '静态碳排放量', value: -16.374 },
      { seriesId: 'dynamic-factors', seriesName: '动态碳排放因子', value: 0.48 },
      { seriesId: 'static-factors', seriesName: '静态碳排放因子', value: -0.583 },
    ])).toBe('1月\n动态碳排放量：6.50 tCO₂e\n静态碳排放量：16.37 tCO₂e\n动态碳排放因子：0.48 kgCO₂e/kWh\n静态碳排放因子：0.58 kgCO₂e/kWh')
    expect(option.yAxis[0].axisPointer.label.formatter({ value: -18 })).toBe('18.00')
    expect(option.yAxis[1].axisPointer.label.formatter({ value: -0.48 })).toBe('0.48')
    wrapper.unmount()
  })

  it('空数据仍保留四个图例系列和坐标，不把未知值补成零', () => {
    const wrapper = mount(ScreenMirrorChart, { props: { period: { kind: 'year', value: '2026' }, data: null } })
    const option = wrapper.findComponent(ChartView).props('option') as { series: Array<{ data: Array<number | null> }> }
    expect(option.series).toHaveLength(4)
    expect(option.series.every(series => series.data.every(value => value === null))).toBe(true)
    expect(wrapper.get('[data-test="mirror-empty"]').text()).toBe('暂无动态与静态碳排放数据')
    wrapper.unmount()
  })

  it('排放量与因子保持在同一绘图区并按各自单位使用左右双轴', () => {
    const wrapper = mount(ScreenMirrorChart, {
      props: {
        period: { kind: 'year', value: '2026' },
        data: { dynamicEmissions: [100, 150], staticEmissions: [110, 160], dynamicFactors: [0.52, 0.54], staticFactors: [0.56, 0.56], emissionUnit: 'tCO₂e', factorUnit: 'kgCO₂e/kWh' },
      },
    })
    const option = wrapper.findComponent(ChartView).props('option') as {
      grid: unknown
      xAxis: { data: string[] }
      yAxis: Array<{ min: number; max: number; position: string }>
      series: Array<{ data: Array<number | null>; xAxisIndex?: number; yAxisIndex: number }>
    }
    expect(Array.isArray(option.grid)).toBe(false)
    expect(Array.isArray(option.xAxis)).toBe(false)
    expect(option.xAxis.data).toHaveLength(12)
    expect(option.yAxis[0]).toMatchObject({ min: -200, max: 200, position: 'left' })
    expect(option.yAxis[1]).toMatchObject({ min: -0.8, max: 0.8, position: 'right' })
    expect(option.series.map(series => series.yAxisIndex)).toEqual([0, 0, 1, 1])
    expect(option.series.every(series => series.xAxisIndex === undefined)).toBe(true)
    expect(option.series[2].data.slice(0, 2)).toEqual([0.52, 0.54])
    expect(option.series[3].data.slice(0, 2)).toEqual([-0.56, -0.56])
    wrapper.unmount()
  })

  it('量标随输入峰值自适应留白，零值和小数值仍有有效对称范围', async () => {
    const data = { dynamicEmissions: [14], staticEmissions: [0], dynamicFactors: [0.64], staticFactors: [0.56], emissionUnit: 'tCO₂e', factorUnit: 'kgCO₂e/kWh' }
    const wrapper = mount(ScreenMirrorChart, { props: { period: { kind: 'year', value: '2026' }, data } })
    const axes = () => (wrapper.findComponent(ChartView).props('option') as { yAxis: Array<{ min: number; max: number; interval: number }> }).yAxis
    expect(axes()[0]).toMatchObject({ min: -20, max: 20, interval: 5 })
    expect(axes()[1]).toMatchObject({ min: -0.8, max: 0.8, interval: 0.2 })
    await wrapper.setProps({ data: { ...data, dynamicEmissions: [400] } })
    expect(axes()[0]).toMatchObject({ min: -500, max: 500, interval: 100 })
    await wrapper.setProps({ data: { ...data, dynamicEmissions: [0.014] } })
    expect(axes()[0].max).toBeCloseTo(0.02)
    expect(axes()[0].interval).toBeCloseTo(0.005)
    await wrapper.setProps({ data: { ...data, dynamicEmissions: [0] } })
    expect(axes()[0]).toMatchObject({ min: -1, max: 1, interval: 0.25 })
    wrapper.unmount()
  })
})
