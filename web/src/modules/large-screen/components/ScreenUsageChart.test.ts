import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChartView from '@/shared/charts/ChartView.vue'
import ScreenUsageChart from './ScreenUsageChart.vue'
import { periodBuckets } from '../models/screen-period'

vi.mock('@/shared/charts/ChartView.vue', () => ({
  default: { name: 'ChartView', props: ['option', 'accessibleLabel'], template: '<div data-test="chart-view" />' },
}))
vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const value = ({
      'largeScreen.periodMonthTick': '{value}月',
      'largeScreen.periodDayTick': '{value}日',
      'largeScreen.missing': '—',
      'largeScreen.usageElectricity': '总用电量',
      'largeScreen.usageTemperature': '室外温度',
      'largeScreen.usageChartLabel': '用量温度图表',
      'largeScreen.usageEmpty': '暂无总用电量与室外温度数据',
    })[key] ?? key
    return value.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('ScreenUsageChart', () => {
  it('用双轴分别展示电量柱与温度直线，保留零值和缺失点', () => {
    const wrapper = mount(ScreenUsageChart, {
      props: {
        period: { kind: 'year', value: '2026' },
        data: { electricity: [0, null, 28], temperature: [null, 22.5, 23], electricityUnit: 'kWh', temperatureUnit: '℃' },
      },
    })
    const option = wrapper.findComponent(ChartView).props('option') as {
      xAxis: { axisLabel: { interval: number } }
      yAxis: Array<{ name: string }>
      series: Array<{ type: string; data: Array<number | null>; yAxisIndex: number; smooth?: boolean; showSymbol?: boolean; connectNulls?: boolean }>
    }
    expect(option.xAxis.axisLabel.interval).toBe(0)
    expect(option.yAxis.map(axis => axis.name)).toEqual(['kWh', '℃'])
    expect(option.series[0]).toMatchObject({ type: 'bar', yAxisIndex: 0 })
    expect(option.series[1]).toMatchObject({ type: 'line', yAxisIndex: 1, smooth: false, showSymbol: true, connectNulls: false })
    expect(option.series.map(series => series.data.slice(0, 3))).toEqual([[0, null, 28], [null, 22.5, 23]])
    wrapper.unmount()
  })

  it('月视图保留日期轴，并在单侧或全部缺失时不删除另一侧或补零', () => {
    const wrapper = mount(ScreenUsageChart, {
      props: {
        period: { kind: 'month', value: '2026-02' },
        data: { electricity: [null], temperature: [18], electricityUnit: 'kWh', temperatureUnit: '℃' },
      },
    })
    const option = wrapper.findComponent(ChartView).props('option') as {
      xAxis: { data: string[] }
      series: Array<{ data: Array<number | null> }>
    }
    expect(option.xAxis.data).toEqual(periodBuckets({ kind: 'month', value: '2026-02' }))
    expect(option.series[0].data.every(value => value === null)).toBe(true)
    expect(option.series[1].data.slice(0, 2)).toEqual([18, null])
    expect(wrapper.find('[data-test="usage-empty"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('默认空数据只显示空态和无值图表结构', () => {
    const wrapper = mount(ScreenUsageChart, { props: { period: { kind: 'year', value: '2026' }, data: null } })
    const option = wrapper.findComponent(ChartView).props('option') as { series: Array<{ data: Array<number | null> }> }
    expect(option.series).toHaveLength(2)
    expect(option.series.every(series => series.data.every(value => value === null))).toBe(true)
    expect(wrapper.get('[data-test="usage-empty"]').text()).toBe('暂无总用电量与室外温度数据')
    wrapper.unmount()
  })
})
