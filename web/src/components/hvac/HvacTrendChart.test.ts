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
    expect(option.series[0].data.map((item: any) => item.value)).toEqual([
      [60_000, 70],
      [120_000, 71],
      [180_000, 72],
      [240_000, null],
    ])
    expect(option.series[0].data.map((item: any) => item.symbol)).toEqual([
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
})
