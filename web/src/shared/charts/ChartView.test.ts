import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChartOption } from './echarts'

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
vi.mock('echarts/charts', () => ({
  LineChart: {},
  BarChart: {},
  PieChart: {},
}))
vi.mock('echarts/components', () => ({
  AriaComponent: {},
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ SVGRenderer: {} }))
vi.mock('@/shared/ui', () => ({
  ElAlert: {
    props: ['title'],
    template: '<section data-test="alert">{{ title }}<slot /></section>',
  },
  ElButton: {
    template: '<button type="button"><slot /></button>',
  },
  ElEmpty: {
    props: ['description'],
    template: '<p>{{ description }}</p>',
  },
  ElSkeleton: {
    template: '<p>正在加载</p>',
  },
}))
vi.mock('@/locales', () => ({
  t: (key: string) => ({
    'common.loading': '正在加载',
    'common.empty': '暂无数据',
    'common.retry': '重新加载',
    'error.chart': '图表加载失败，请重新加载。',
  })[key] ?? key,
}))

import ChartView from './ChartView.vue'

const registeredModules = mocks.use.mock.calls.at(-1)?.[0]

const option: ChartOption = {
  xAxis: { type: 'category', data: ['一月'] },
  yAxis: { type: 'value' },
  series: [{ type: 'line', data: [10] }],
}

const tokenStyle = [
  '--bec-chart-series-1: rgb(1, 2, 3)',
  '--bec-chart-series-2: rgb(4, 5, 6)',
  '--bec-chart-series-3: rgb(7, 8, 9)',
  '--bec-chart-series-4: rgb(10, 11, 12)',
  '--bec-color-text-primary: rgb(20, 21, 22)',
  '--bec-color-text-secondary: rgb(30, 31, 32)',
  '--bec-color-border: rgb(40, 41, 42)',
  '--bec-color-surface: rgb(50, 51, 52)',
  '--bec-font-family-body: Test Sans',
  '--bec-chart-font-size: 18px',
].join('; ')

describe('ChartView', () => {
  let resizeCallback: ResizeObserverCallback
  let themeCallback: MutationCallback
  const resizeObserve = vi.fn()
  const resizeDisconnect = vi.fn()
  const themeObserve = vi.fn()
  const themeDisconnect = vi.fn()
  const wrappers: Array<ReturnType<typeof mount>> = []

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.init.mockReturnValue({
      setOption: mocks.setOption,
      resize: mocks.resize,
      dispose: mocks.dispose,
    })
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => 640,
    })
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get: () => 360,
    })
    class ResizeObserverMock {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback
      }

      observe = resizeObserve
      disconnect = resizeDisconnect
      unobserve = vi.fn()
    }
    class MutationObserverMock {
      constructor(callback: MutationCallback) {
        themeCallback = callback
      }

      observe = themeObserve
      disconnect = themeDisconnect
      takeRecords = vi.fn()
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    vi.stubGlobal('MutationObserver', MutationObserverMock)
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.unstubAllGlobals()
  })

  function mountChart(props: Partial<InstanceType<typeof ChartView>['$props']> = {}) {
    const wrapper = mount(ChartView, {
      attachTo: document.body,
      attrs: { style: tokenStyle },
      props: {
        option,
        accessibleLabel: '月度能耗趋势图',
        ...props,
      },
    })
    wrappers.push(wrapper)
    return wrapper
  }

  it('registers the requested SVG chart modules and initializes only after a sized container exists', () => {
    const wrapper = mountChart()

    expect(registeredModules).toHaveLength(8)
    expect(mocks.init).toHaveBeenCalledWith(
      wrapper.get('[data-test="chart-canvas"]').element,
      undefined,
      { renderer: 'svg', locale: 'ZH' },
    )
    expect(mocks.setOption).toHaveBeenCalledWith(
      expect.objectContaining({
        color: [
          'rgb(1, 2, 3)',
          'rgb(4, 5, 6)',
          'rgb(7, 8, 9)',
          'rgb(10, 11, 12)',
        ],
        textStyle: expect.objectContaining({
          color: 'rgb(20, 21, 22)',
          fontFamily: 'Test Sans',
          fontSize: 18,
        }),
        tooltip: expect.objectContaining({
          appendToBody: false,
          confine: true,
          renderMode: 'richText',
          backgroundColor: 'rgb(50, 51, 52)',
          borderColor: 'rgb(40, 41, 42)',
        }),
        aria: expect.objectContaining({
          enabled: true,
          description: '月度能耗趋势图',
        }),
      }),
      { notMerge: true, lazyUpdate: false },
    )
    expect(wrapper.get('[data-test="chart-canvas"]').attributes('aria-label')).toBe('月度能耗趋势图')
  })

  it('replaces the option without retaining an obsolete series and responds to parent resize', async () => {
    const wrapper = mountChart()
    const nextOption: ChartOption = {
      xAxis: { type: 'category', data: ['二月'] },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: [20] }],
    }

    await wrapper.setProps({ option: nextOption })

    const latestOption = mocks.setOption.mock.calls.at(-1)![0]
    expect(latestOption.series).toEqual([{ type: 'bar', data: [20] }])
    expect(mocks.setOption.mock.calls.at(-1)![1]).toEqual({ notMerge: true, lazyUpdate: false })

    resizeCallback([], {} as ResizeObserver)
    expect(mocks.resize).toHaveBeenCalledTimes(1)
  })

  it('renders loading and empty states without inventing chart series', async () => {
    const wrapper = mountChart({ loading: true })

    expect(wrapper.get('[data-test="chart-loading"]').text()).toContain('正在加载')
    expect(mocks.init).not.toHaveBeenCalled()

    await wrapper.setProps({ loading: false, empty: true })
    await nextTick()
    expect(wrapper.get('[data-test="chart-empty"]').text()).toContain('暂无数据')
    expect(mocks.init).not.toHaveBeenCalled()
  })

  it('keeps failures local in Chinese and allows retry', async () => {
    mocks.init
      .mockImplementationOnce(() => { throw new Error('init failed') })
      .mockReturnValueOnce({
        setOption: mocks.setOption,
        resize: mocks.resize,
        dispose: mocks.dispose,
      })

    const wrapper = mountChart()

    await nextTick()
    expect(wrapper.get('[data-test="chart-error"]').text()).toContain('图表加载失败，请重新加载。')
    await wrapper.get('button').trigger('click')
    await nextTick()
    expect(mocks.init).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="chart-error"]').exists()).toBe(false)
  })

  it('reapplies parent tokens on a data-theme change and releases both observers and the instance', async () => {
    const wrapper = mountChart()
    const initialSetOptionCount = mocks.setOption.mock.calls.length
    wrapper.element.style.setProperty('--bec-chart-series-1', 'rgb(90, 91, 92)')

    themeCallback([{
      type: 'attributes',
      attributeName: 'data-theme',
    } as MutationRecord], {} as MutationObserver)
    await nextTick()

    expect(mocks.setOption).toHaveBeenCalledTimes(initialSetOptionCount + 1)
    expect(mocks.setOption.mock.calls.at(-1)![0].color[0]).toBe('rgb(90, 91, 92)')

    wrapper.unmount()
    expect(resizeObserve).toHaveBeenCalledTimes(1)
    expect(resizeDisconnect).toHaveBeenCalledTimes(1)
    expect(themeObserve).toHaveBeenCalledWith(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    })
    expect(themeDisconnect).toHaveBeenCalledTimes(1)
    expect(mocks.dispose).toHaveBeenCalledTimes(1)
  })
})
