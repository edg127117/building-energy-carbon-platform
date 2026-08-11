import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { HvacTrendGroup } from '@/domain/hvacHistoryTrends'

const mocks = vi.hoisted(() => ({
  use: vi.fn(),
  init: vi.fn(),
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
}))

vi.mock('echarts/core', () => ({
  use: mocks.use,
  init: mocks.init,
}))
vi.mock('echarts/charts', () => ({ LineChart: {} }))
vi.mock('echarts/components', () => ({
  DataZoomComponent: {},
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import HvacTrendChart from './HvacTrendChart.vue'

type RenderedDatum = {
  value: [number, number | null]
  symbol: string
}

const group: HvacTrendGroup = {
  unit: '%',
  series: [{
    id: 'I1',
    code: 'PUMP_EFF',
    label: '水泵效率',
    unit: '%',
    precision: 1,
    points: [
      {
        time: 60_000,
        average: 70,
        minimum: 69,
        maximum: 71,
        sampleCount: 1,
        dataQuality: 0,
      },
      {
        time: 120_000,
        average: 71,
        minimum: 70,
        maximum: 72,
        sampleCount: 2,
        dataQuality: 1,
      },
      {
        time: 180_000,
        average: 72,
        minimum: 71,
        maximum: 73,
        sampleCount: 3,
        dataQuality: 2,
      },
      {
        time: 240_000,
        average: null,
        minimum: null,
        maximum: null,
        sampleCount: 0,
        dataQuality: null,
      },
    ],
  }],
}

function renderSymbols(
  points: HvacTrendGroup['series'][number]['points'],
): string[] {
  const wrapper = mount(HvacTrendChart, {
    props: {
      group: {
        ...group,
        series: [{ ...group.series[0]!, points }],
      },
      from: 0,
      to: 300_000,
      resolutionMinutes: 1,
    },
  })
  const symbols = mocks.setOption.mock.calls.at(-1)![0].series[0].data
    .map((item: RenderedDatum) => item.symbol)
  wrapper.unmount()
  return symbols
}

describe('HvacTrendChart', () => {
  let resizeCallback: ResizeObserverCallback
  const observe = vi.fn()
  const disconnect = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.init.mockReturnValue({
      setOption: mocks.setOption,
      resize: mocks.resize,
      dispose: mocks.dispose,
    })
    observe.mockReset()
    disconnect.mockReset()
    class ResizeObserverMock {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback
      }
      observe = observe
      disconnect = disconnect
      unobserve = vi.fn()
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))
  })

  it('renders quality-aware series and preserves null gaps', () => {
    const wrapper = mount(HvacTrendChart, {
      props: {
        group,
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    expect(mocks.init).toHaveBeenCalledTimes(1)
    expect(mocks.setOption).toHaveBeenCalledTimes(1)
    const option = mocks.setOption.mock.calls[0][0]
    expect(option.series).toHaveLength(1)
    expect(option.series[0]).toMatchObject({
      name: '水泵效率',
      type: 'line',
      connectNulls: false,
    })
    expect(option.series[0].data.map((item: RenderedDatum) => item.value)).toEqual([
      [60_000, 70],
      [120_000, 71],
      [180_000, 72],
      [240_000, null],
    ])
    expect(option.series[0].data.map((item: RenderedDatum) => item.symbol)).toEqual([
      'none',
      'circle',
      'diamond',
      'none',
    ])
    const tooltip = option.tooltip.formatter([
      {
        marker: '<i />',
        data: option.series[0].data[2],
      },
    ])
    expect(tooltip).toContain('平均值 72.0 %')
    expect(tooltip).toContain('最小值 71.0')
    expect(tooltip).toContain('最大值 73.0')
    expect(tooltip).toContain('样本数 3')
    expect(tooltip).toContain('Q2 典型值')
    expect(wrapper.text()).toContain('单位：%')
    expect(wrapper.text()).toContain('缺口保持断线')
  })

  it('shows only Q0 points that have no adjacent line segment', () => {
    const isolated = renderSymbols([{
      time: 60_000,
      average: 3.2,
      minimum: 3.2,
      maximum: 3.2,
      sampleCount: 1,
      dataQuality: 0,
    }])
    expect(isolated).toEqual(['circle'])

    const continuous = renderSymbols([
      {
        time: 60_000,
        average: 3.2,
        minimum: 3.2,
        maximum: 3.2,
        sampleCount: 1,
        dataQuality: 0,
      },
      {
        time: 120_000,
        average: 3.3,
        minimum: 3.3,
        maximum: 3.3,
        sampleCount: 1,
        dataQuality: 0,
      },
    ])
    expect(continuous).toEqual(['none', 'none'])

    const separated = renderSymbols([
      {
        time: 60_000,
        average: 3.2,
        minimum: 3.2,
        maximum: 3.2,
        sampleCount: 1,
        dataQuality: 0,
      },
      {
        time: 120_000,
        average: null,
        minimum: null,
        maximum: null,
        sampleCount: 0,
        dataQuality: null,
      },
      {
        time: 180_000,
        average: 3.4,
        minimum: 3.4,
        maximum: 3.4,
        sampleCount: 1,
        dataQuality: 0,
      },
    ])
    expect(separated).toEqual(['circle', 'none', 'circle'])
  })

  it('updates without reinitializing, resizes and disposes on unmount', async () => {
    const wrapper = mount(HvacTrendChart, {
      props: {
        group,
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    await wrapper.setProps({ to: 360_000 })
    expect(mocks.init).toHaveBeenCalledTimes(1)
    expect(mocks.setOption).toHaveBeenCalledTimes(2)
    expect(mocks.setOption.mock.calls[1][0].xAxis.min).toBe(0)
    expect(mocks.setOption.mock.calls[1][0].xAxis.max).toBe(360_000)

    resizeCallback([], {} as ResizeObserver)
    expect(mocks.resize).toHaveBeenCalledTimes(1)
    expect(observe).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    expect(disconnect).toHaveBeenCalledTimes(1)
    expect(mocks.dispose).toHaveBeenCalledTimes(1)
  })

  it('uses an explicit title for dimensionless groups', () => {
    const wrapper = mount(HvacTrendChart, {
      props: {
        group: { ...group, unit: '' },
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    expect(wrapper.text()).toContain('无量纲')
  })

  it('uses a bounded data-driven extent for a single finite value', () => {
    mount(HvacTrendChart, {
      props: {
        group: {
          unit: '',
          series: [{
            ...group.series[0]!,
            unit: '',
            precision: 2,
            points: [{
              time: 60_000,
              average: 3.2,
              minimum: 3.2,
              maximum: 3.2,
              sampleCount: 1,
              dataQuality: 0,
            }],
          }],
        },
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    const yAxis = mocks.setOption.mock.calls[0][0].yAxis
    expect(yAxis.name).toBe('无量纲')
    expect(yAxis.min).toBeCloseTo(2.88)
    expect(yAxis.max).toBeCloseTo(3.52)
    expect(yAxis.axisLabel.formatter(2.9339999999999997)).toBe('2.93')
  })

  it('keeps one data-driven axis for all finite values in the same unit group', () => {
    const sourceSeries = group.series[0]!
    const sourcePoint = sourceSeries.points[0]!
    mount(HvacTrendChart, {
      props: {
        group: {
          unit: '%',
          series: [
            {
              ...sourceSeries,
              id: 'tower',
              points: [{ ...sourcePoint, average: 58.4 }],
            },
            {
              ...sourceSeries,
              id: 'pump',
              points: [{ ...sourcePoint, average: 74.2 }],
            },
          ],
        },
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    const yAxis = mocks.setOption.mock.calls[0][0].yAxis
    expect(yAxis).toMatchObject({ name: '%', scale: true })
    expect(yAxis.min).toBeUndefined()
    expect(yAxis.max).toBeUndefined()
    expect(yAxis.axisLabel.formatter(58.400000000000006)).toBe('58.4')
  })

  it('shows an accessible empty state without inventing an axis extent', () => {
    const wrapper = mount(HvacTrendChart, {
      props: {
        group: {
          ...group,
          series: [{
            ...group.series[0]!,
            points: [{
              time: 60_000,
              average: null,
              minimum: null,
              maximum: null,
              sampleCount: 0,
              dataQuality: null,
            }],
          }],
        },
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    expect(wrapper.find('.trend-chart__empty').text()).toBe('当前时段无有效数据')
    expect(wrapper.find('.trend-chart__empty').attributes('role')).toBe('status')
    const yAxis = mocks.setOption.mock.calls[0][0].yAxis
    expect(yAxis.name).toBe('%')
    expect(yAxis.min).toBeUndefined()
    expect(yAxis.max).toBeUndefined()
  })

  it('separates the axis unit and lets each external legend button hide its series', async () => {
    const firstSeries = group.series[0]!
    const wrapper = mount(HvacTrendChart, {
      props: {
        group: {
          ...group,
          series: [
            firstSeries,
            {
              ...firstSeries,
              id: 'I2',
              code: 'TOWER_EFF',
              label: '冷却塔效率',
            },
          ],
        },
        from: 0,
        to: 300_000,
        resolutionMinutes: 1,
      },
    })

    const legendButtons = wrapper.findAll('.trend-chart__series-item')
    expect(legendButtons.map((item) => item.text()))
      .toEqual(['水泵效率', '冷却塔效率'])
    const swatches = wrapper.findAll('.trend-chart__series-swatch')
    expect(swatches).toHaveLength(2)
    expect(swatches[0]!.attributes('style')).not.toBe(swatches[1]!.attributes('style'))
    expect(wrapper.find('.trend-chart__axis-unit').exists()).toBe(false)
    expect(mocks.setOption.mock.calls[0][0].yAxis).toMatchObject({
      name: '%',
      type: 'value',
    })
    expect(mocks.setOption.mock.calls[0][0].grid.containLabel).toBe(true)
    expect(legendButtons.map((button) => button.attributes('aria-pressed')))
      .toEqual(['true', 'true'])

    await legendButtons[0]!.trigger('click')

    expect(legendButtons[0]!.attributes('aria-pressed')).toBe('false')
    expect(legendButtons[1]!.attributes('aria-pressed')).toBe('true')
    expect(legendButtons[0]!.classes()).toContain('trend-chart__series-item--hidden')
    expect(mocks.setOption.mock.calls.at(-1)![0].legend).toMatchObject({
      show: false,
      selected: {
        '水泵效率': false,
        '冷却塔效率': true,
      },
    })
  })
})
