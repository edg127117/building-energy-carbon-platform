import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChartView from '@/shared/charts/ChartView.vue'
import ScreenPlanChart from './ScreenPlanChart.vue'

vi.mock('@/shared/charts/ChartView.vue', () => ({
  default: { name: 'ChartView', props: ['option', 'accessibleLabel'], template: '<div data-test="chart-view" />' },
}))
vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) => {
    const value = ({
      'largeScreen.periodMonthTick': '{value}月',
      'largeScreen.monthShort': '月',
      'largeScreen.percent': '%',
      'largeScreen.missing': '—',
      'largeScreen.planReductionRate': '计划消减率',
      'largeScreen.planAnnualBaseline': '年基准排放量',
      'largeScreen.planQuotaAccessible': '年剩余配额：{value} {unit}，{state}',
      'largeScreen.planAnnualRemainingQuota': '年剩余配额',
      'largeScreen.planQuotaRemaining': '剩余',
      'largeScreen.planQuotaExceeded': '超额',
      'largeScreen.planQuotaUsed': '恰好用完',
      'largeScreen.planQuotaUnknown': '待获取',
      'largeScreen.planBaseline': '月基准排放量',
      'largeScreen.planQuota': '月配额',
      'largeScreen.planActual': '实际月排放量',
      'largeScreen.planChartLabel': '计划图表',
      'largeScreen.planEmpty': '暂无计划与配额数据',
    })[key] ?? key
    return value.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
  },
}))

describe('ScreenPlanChart', () => {
  it('月基准、月配额和实际月排放悬停值统一保留两位，缺失不补零', () => {
    const wrapper = mount(ScreenPlanChart, { props: { period: { kind: 'year', value: '2026' }, data: null } })
    const option = wrapper.findComponent(ChartView).props('option') as { tooltip: { valueFormatter: (value: unknown) => string } }
    expect(option.tooltip.valueFormatter(14.020109)).toBe('14.02')
    expect(option.tooltip.valueFormatter(10)).toBe('10.00')
    expect(option.tooltip.valueFormatter(8.5)).toBe('8.50')
    expect(option.tooltip.valueFormatter(0)).toBe('0.00')
    expect(option.tooltip.valueFormatter(null)).toBe('—')
    wrapper.unmount()
  })

  it('保留全年月份并突出选中月，且只绘制两条直线点图和实际柱', () => {
    const wrapper = mount(ScreenPlanChart, {
      props: {
        period: { kind: 'month', value: '2026-03' },
        data: {
          annualBaseline: 160,
          reductionPercent: 2,
          remainingQuota: -4.5,
          baseline: [12, 11, null],
          quota: [10, 9, 8],
          actual: [8, 0, 9],
          unit: 'tCO₂e',
        },
      },
    })

    const option = wrapper.findComponent(ChartView).props('option') as {
      xAxis: { data: string[]; axisLabel: { interval: number; formatter: (value: string, index: number) => string } }
      series: Array<{ type: string; data: Array<number | null>; smooth?: boolean; showSymbol?: boolean; connectNulls?: boolean }>
    }
    expect(option.xAxis.data).toHaveLength(12)
    expect(option.xAxis.axisLabel.interval).toBe(0)
    expect(option.xAxis.axisLabel.formatter('3月', 2)).toBe('{selected|3月}')
    expect(option.series[0]).toMatchObject({ type: 'line', smooth: false, showSymbol: true, connectNulls: false })
    expect(option.series[1]).toMatchObject({ type: 'line', smooth: false, showSymbol: true, connectNulls: false })
    expect(option.series[2]).toMatchObject({ type: 'bar' })
    expect(option.series.map(series => series.data.slice(0, 3))).toEqual([[12, 11, null], [10, 9, 8], [8, 0, 9]])
    expect(wrapper.get('[data-test="plan-quota-state"]').attributes('aria-label')).toContain('超额')
    expect(wrapper.text()).toContain('-4.5')
    expect(wrapper.get('[data-test="plan-annual-baseline"]').text()).toBe('160')
    expect(wrapper.get('[data-test="plan-quota-state"]').text()).not.toContain('超额')
    wrapper.unmount()
  })

  it('明确区分剩余、用完和未知配额，空图只保留结构不补零', async () => {
    const wrapper = mount(ScreenPlanChart, { props: { period: { kind: 'year', value: '2026' }, data: null } })
    const option = wrapper.findComponent(ChartView).props('option') as { series: Array<{ data: Array<number | null> }> }
    expect(option.series).toHaveLength(3)
    expect(option.series.every(series => series.data.every(value => value === null))).toBe(true)
    expect(wrapper.get('[data-test="plan-empty"]').text()).toBe('暂无计划与配额数据')
    expect(wrapper.get('[data-test="plan-quota-state"]').attributes('aria-label')).toContain('待获取')

    await wrapper.setProps({ data: { reductionPercent: 2, remainingQuota: 3, baseline: [], quota: [], actual: [], unit: 'tCO₂e' } })
    expect(wrapper.get('[data-test="plan-quota-state"]').attributes('aria-label')).toContain('剩余')
    expect(wrapper.text()).toContain('+3')
    await wrapper.setProps({ data: { reductionPercent: 2, remainingQuota: 0, baseline: [], quota: [], actual: [], unit: 'tCO₂e' } })
    expect(wrapper.get('[data-test="plan-quota-state"]').attributes('aria-label')).toContain('恰好用完')
    wrapper.unmount()
  })

  it('固定年基准不随月份或月基准曲线变化，缺少独立年基准时不反算', async () => {
    const data = { annualBaseline: 160, reductionPercent: 15, remainingQuota: 32, baseline: [12, 10], quota: [], actual: [], unit: 'tCO₂e' }
    const wrapper = mount(ScreenPlanChart, { props: { period: { kind: 'year', value: '2026' }, data } })
    expect(wrapper.get('[data-test="plan-annual-baseline"]').text()).toBe('160')
    await wrapper.setProps({ period: { kind: 'month', value: '2026-02' }, data: { ...data, baseline: [500, 600] } })
    expect(wrapper.get('[data-test="plan-annual-baseline"]').text()).toBe('160')
    expect(wrapper.get('[data-test="plan-quota-state"]').text()).toBe('+32tCO₂e')
    await wrapper.setProps({ data: { ...data, annualBaseline: 0 } })
    expect(wrapper.get('[data-test="plan-annual-baseline"]').text()).toBe('0')
    await wrapper.setProps({ data: { ...data, annualBaseline: undefined } })
    expect(wrapper.get('[data-test="plan-annual-baseline"]').text()).toBe('—')
    wrapper.unmount()
  })
})
