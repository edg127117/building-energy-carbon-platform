import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import type {
  HvacHistoryError,
  useHvacHistoryTrends,
} from '@/composables/useHvacHistoryTrends'
import type {
  HvacHistoryMode,
  HvacHistoryPointOption,
  HvacHistoryPreset,
  HvacTrendGroup,
} from '@/domain/hvacHistoryTrends'

type HistoryState = ReturnType<typeof useHvacHistoryTrends>

const holder = vi.hoisted(() => ({ state: null as HistoryState | null }))

vi.mock('@/composables/useHvacHistoryTrends', () => ({
  useHvacHistoryTrends: () => holder.state!,
}))

import HvacHistoryPanel from './HvacHistoryPanel.vue'

const dataGroup: HvacTrendGroup = {
  unit: '%',
  series: [{
    id: 'I1',
    code: 'PUMP_EFF',
    label: '水泵效率',
    unit: '%',
    precision: 1,
    points: [{
      time: 60_000,
      average: 72,
      minimum: 71,
      maximum: 73,
      sampleCount: 1,
      dataQuality: 0,
    }],
  }],
}

function state(): HistoryState {
  return {
    mode: ref<HvacHistoryMode>('indicators'),
    preset: ref<HvacHistoryPreset>('24h'),
    customFrom: ref<number | null>(null),
    customTo: ref<number | null>(null),
    pointOptions: ref<HvacHistoryPointOption[]>([
      { pointId: 'P1', pointCode: 'WCR1_TWin', label: '冷冻水进水温度', unit: '℃', precision: 1 },
      { pointId: 'P2', pointCode: 'WCR1_GW', label: '冷冻水流量', unit: 'm³/h', precision: 1 },
    ]),
    selectedPointIds: ref<string[]>([]),
    groups: ref<HvacTrendGroup[]>([dataGroup]),
    responseRange: ref<{ from: number; to: number } | null>({ from: 0, to: 300_000 }),
    resolutionMinutes: ref<number | null>(1),
    loading: ref(false),
    error: ref<HvacHistoryError | null>(null),
    stale: ref(false),
    updatedAt: ref<number | null>(300_000),
    setBuildingContext: vi.fn().mockResolvedValue(undefined),
    setMode: vi.fn().mockResolvedValue(undefined),
    setPreset: vi.fn().mockResolvedValue(undefined),
    setCustomRange: vi.fn().mockResolvedValue(undefined),
    setSelectedPointIds: vi.fn().mockResolvedValue(undefined),
    refresh: vi.fn().mockResolvedValue(undefined),
    dispose: vi.fn(),
  }
}

const stubs = {
  HvacTrendChart: {
    props: ['group'],
    template: '<div class="trend-stub">{{ group.unit || "无量纲" }}</div>',
  },
  'a-segmented': {
    props: ['options'],
    template: '<button data-test="mode-change" @click="$emit(\'change\', \'points\')"><span v-for="item in options" :key="item.value">{{ item.label }}</span></button>',
  },
  'a-button': {
    inheritAttrs: false,
    props: ['disabled'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'a-select': {
    template: '<button data-test="point-select" @click="$emit(\'change\', [\'P1\'])">选择测点</button>',
  },
  'a-range-picker': {
    template: '<button data-test="range-picker" @click="$emit(\'change\', null)">时间范围</button>',
  },
  'a-alert': {
    props: ['message', 'description'],
    template: '<div class="alert-stub">{{ message }} {{ description }}</div>',
  },
  'a-skeleton': { template: '<div class="skeleton-stub">加载中</div>' },
  'a-empty': {
    props: ['description'],
    template: '<div class="empty-stub">{{ description }}</div>',
  },
}

const indicators = [{
  indicatorId: 'I1',
  indicatorCode: 'WCR_COP',
  label: '冷水机组 COP',
  tone: 'blue',
  value: 5.8,
  displayValue: '5.80',
  unit: '',
  minuteStart: 60_000,
  status: 'SUCCESS',
  statusLabel: '计算成功',
  summaryText: '最新分钟计算完成',
  supportingText: null,
  dataQuality: 0,
  formulaVersion: 'V1',
  reasonCode: null,
  missingInputs: [],
}]

function mountPanel() {
  return mount(HvacHistoryPanel, {
    props: {
      buildingId: 'BLD001',
      indicators,
      snapshotPoints: [],
    },
    global: { stubs },
  })
}

describe('HvacHistoryPanel', () => {
  beforeEach(() => {
    holder.state = state()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('shows approved modes, default range and response summary', () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('历史运行趋势')
    expect(wrapper.text()).toContain('能效指标')
    expect(wrapper.text()).toContain('原始测点')
    expect(wrapper.text()).toContain('最近 24 小时')
    expect(wrapper.text()).toContain('自动分辨率')
    expect(wrapper.text()).toContain('1 分钟')
    expect(holder.state.setBuildingContext).toHaveBeenCalledTimes(1)
  })

  it('shows point selection count and no-request guidance in point mode', () => {
    holder.state.mode.value = 'points'
    holder.state.groups.value = []
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('已选 0/8')
    expect(wrapper.find('[data-test="point-select"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('请选择 1～8 个测点查看历史趋势')
  })

  it('routes mode, preset, point selection and refresh events to state only', async () => {
    holder.state.mode.value = 'points'
    holder.state.selectedPointIds.value = ['P1']
    const wrapper = mountPanel()

    await wrapper.get('[data-test="mode-change"]').trigger('click')
    const sevenDay = wrapper.findAll('button')
      .find((button) => button.text().includes('7 天'))!
    await sevenDay.trigger('click')
    await wrapper.get('[data-test="point-select"]').trigger('click')
    const refresh = wrapper.findAll('button')
      .find((button) => button.text().includes('刷新趋势'))!
    await refresh.trigger('click')

    expect(holder.state.setMode).toHaveBeenCalledWith('points')
    expect(holder.state.setPreset).toHaveBeenCalledWith('7d')
    expect(holder.state.setSelectedPointIds).toHaveBeenCalledWith(['P1'])
    expect(holder.state.refresh).toHaveBeenCalledTimes(1)
  })

  it('auto refreshes a visible relative range every 30 seconds and stops on unmount', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const wrapper = mountPanel()

    await vi.advanceTimersByTimeAsync(30_000)
    expect(holder.state.refresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(holder.state.refresh).toHaveBeenCalledTimes(1)
  })

  it('skips automatic refresh for fixed, hidden, loading and empty point states', async () => {
    vi.useFakeTimers()
    let visibility: DocumentVisibilityState = 'visible'
    vi.spyOn(document, 'visibilityState', 'get')
      .mockImplementation(() => visibility)
    const wrapper = mountPanel()

    holder.state.preset.value = 'custom'
    await vi.advanceTimersByTimeAsync(30_000)

    holder.state.preset.value = '24h'
    holder.state.loading.value = true
    await vi.advanceTimersByTimeAsync(30_000)

    holder.state.loading.value = false
    visibility = 'hidden'
    await vi.advanceTimersByTimeAsync(30_000)

    visibility = 'visible'
    holder.state.mode.value = 'points'
    holder.state.selectedPointIds.value = []
    await vi.advanceTimersByTimeAsync(30_000)

    expect(holder.state.refresh).not.toHaveBeenCalled()

    holder.state.mode.value = 'indicators'
    await vi.advanceTimersByTimeAsync(30_000)
    expect(holder.state.refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('validates incomplete custom range before calling the composable', async () => {
    holder.state.preset.value = 'custom'
    const wrapper = mountPanel()

    await wrapper.get('[data-test="range-picker"]').trigger('click')

    expect(wrapper.text()).toContain('请选择完整的开始和结束时间')
    expect(holder.state.setCustomRange).not.toHaveBeenCalled()
  })

  it('renders one chart per unit group and reports partial empty series', () => {
    holder.state.groups.value = [
      dataGroup,
      {
        unit: '℃',
        series: [{
          id: 'P1',
          code: 'WCR1_TWin',
          label: '冷冻水进水温度',
          unit: '℃',
          precision: 1,
          points: [],
        }],
      },
    ]
    const wrapper = mountPanel()

    expect(wrapper.findAll('.trend-stub')).toHaveLength(2)
    expect(wrapper.text()).toContain('部分序列暂无数据')
    expect(wrapper.text()).toContain('冷冻水进水温度')
  })

  it('explains that four indicators are grouped into three unit charts', () => {
    const firstSeries = dataGroup.series[0]!
    holder.state.groups.value = [
      {
        unit: '',
        series: [{ ...firstSeries, id: 'I1', code: 'WCR_COP' }],
      },
      {
        unit: '%',
        series: [
          { ...firstSeries, id: 'I2', code: 'TOWER_EFF' },
          { ...firstSeries, id: 'I3', code: 'PUMP_EFF' },
        ],
      },
      {
        unit: 'W/(m³·h)',
        series: [{ ...firstSeries, id: 'I4', code: 'AHU_POW_EFF' }],
      },
    ]

    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('4 项指标 · 3 个单位图表 · 同单位合并对比')
  })

  it('distinguishes loading, stale, forbidden and unavailable states', async () => {
    holder.state.loading.value = true
    holder.state.groups.value = []
    let wrapper = mountPanel()
    expect(wrapper.find('.skeleton-stub').exists()).toBe(true)
    wrapper.unmount()

    holder.state = state()
    holder.state.stale.value = true
    holder.state.error.value = { status: 503, message: '历史趋势暂不可用，请稍后重试' }
    wrapper = mountPanel()
    expect(wrapper.text()).toContain('当前显示上次成功结果')
    expect(wrapper.findAll('.trend-stub')).toHaveLength(1)
    wrapper.unmount()

    holder.state = state()
    holder.state.groups.value = []
    holder.state.error.value = { status: 403, message: '当前账号无权查看该建筑历史' }
    wrapper = mountPanel()
    expect(wrapper.text()).toContain('当前账号无权查看该建筑历史')
    expect(wrapper.text()).not.toContain('重试趋势请求')
    wrapper.unmount()

    holder.state = state()
    holder.state.groups.value = []
    holder.state.error.value = { status: 503, message: '历史趋势暂不可用，请稍后重试' }
    wrapper = mountPanel()
    expect(wrapper.text()).toContain('历史趋势暂不可用')
    expect(wrapper.text()).toContain('重试趋势请求')
  })

  it('disposes the request lifecycle on unmount', () => {
    const wrapper = mountPanel()
    wrapper.unmount()
    expect(holder.state.dispose).toHaveBeenCalledTimes(1)
  })
})
