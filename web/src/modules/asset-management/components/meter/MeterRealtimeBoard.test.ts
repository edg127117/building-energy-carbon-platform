import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { AssetEquipmentDetail, AssetPointReading } from '../../models/assets'
import MeterPointReadingTable from './MeterPointReadingTable.vue'
import ThreePhaseMeterBoard from './ThreePhaseMeterBoard.vue'
import SinglePhaseMeterBoard from './SinglePhaseMeterBoard.vue'
import MeterRealtimeBoard from './MeterRealtimeBoard.vue'
import MeterRealtimeTrendChart from './MeterRealtimeTrendChart.vue'

// Mock getEquipmentTrendHistory
vi.mock('../../api/assets', () => ({
  getEquipmentTrendHistory: vi.fn().mockResolvedValue({
    equipmentId: 'eq-3p-1',
    startTime: '2026-09-21T10:00:00Z',
    endTime: '2026-09-21T11:00:00Z',
    series: [],
  }),
}))

// Mock useEquipmentReadings composable
vi.mock('../../composables/use-equipment-readings', () => {
  return {
    useEquipmentReadings: () => ({
      readings: ref({
        equipmentId: 'eq-3p-1',
        buildingId: 'b-1',
        generatedAt: 1700000000000,
        points: [
          { pointId: 'p1', pointCode: 'P_TOTAL', pointName: '总有功功率', unit: 'kW', value: 32.5, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
          { pointId: 'p2', pointCode: 'EPP', pointName: '正向电能', unit: 'kWh', value: 148200, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
          { pointId: 'p3', pointCode: 'I_A', pointName: 'A相电流', unit: 'A', value: 48.2, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
          { pointId: 'p4', pointCode: 'I_B', pointName: 'B相电流', unit: 'A', value: 47.9, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
          { pointId: 'p5', pointCode: 'I_C', pointName: 'C相电流', unit: 'A', value: 48.0, dataQuality: 0, status: 'NORMAL', usageStatus: 'NORMAL', eventTime: null, receivedTime: null, reason: null },
        ] as AssetPointReading[],
      }),
      loading: ref(false),
      error: ref(null),
      load: vi.fn(),
      clear: vi.fn(),
    }),
  }
})

describe('Meter Realtime Components', () => {
  const mockPoints: AssetPointReading[] = [
    {
      pointId: 'p1',
      pointCode: 'P_TOTAL',
      pointName: '总有功功率',
      unit: 'kW',
      value: 32.5,
      dataQuality: 0,
      status: 'NORMAL',
      usageStatus: 'NORMAL',
      eventTime: null,
      receivedTime: null,
      reason: null,
    },
    {
      pointId: 'p2',
      pointCode: 'EPP',
      pointName: '正向总电量',
      unit: 'kWh',
      value: 148200.5,
      dataQuality: 0,
      status: 'NORMAL',
      usageStatus: 'NORMAL',
      eventTime: null,
      receivedTime: null,
      reason: null,
    },
  ]

  it('renders MeterPointReadingTable with friendly Chinese names and Q0 quality badges', () => {
    const wrapper = mount(MeterPointReadingTable, {
      props: { points: mockPoints },
      global: {
        stubs: {
          ElTable: { template: '<div class="el-table"><slot /></div>' },
          ElTableColumn: {
            props: ['label'],
            template: '<div class="el-table-column"><span class="col-label">{{ label }}</span><slot :row="{ pointCode: \'P_TOTAL\', pointName: \'总有功功率\', value: 32.5, unit: \'kW\', dataQuality: 0 }" /></div>',
          },
          ElTag: { template: '<span class="el-tag"><slot /></span>' },
          ElTooltip: { template: '<div><slot /></div>' },
          ElEmpty: { template: '<div>Empty</div>' },
        },
      },
    })

    expect(wrapper.text()).toContain('实时测点读数清单')
    expect(wrapper.text()).toContain('Q0 现场实测')
    wrapper.unmount()
  })

  it('renders ThreePhaseMeterBoard with core KPI metrics and balance card', () => {
    const wrapper = mount(ThreePhaseMeterBoard, {
      props: {
        points: mockPoints,
        trendRecords: [],
        loading: false,
      },
      global: {
        stubs: {
          MeterRealtimeTrendChart: { template: '<div class="mock-chart">Chart</div>' },
          MeterPointReadingTable: { template: '<div class="mock-table">Table</div>' },
          ElTag: { template: '<span class="el-tag"><slot /></span>' },
        },
      },
    })

    expect(wrapper.text()).toContain('实时总有功功率')
    expect(wrapper.text()).toContain('累计用电量')
    expect(wrapper.text()).toContain('A / B / C 三相对称平衡负荷对比')
    wrapper.unmount()
  })

  it('renders SinglePhaseMeterBoard with single-phase KPIs and electrical param grid', () => {
    const singlePoints: AssetPointReading[] = [
      {
        pointId: 'sp1',
        pointCode: 'POWER',
        pointName: '用电功率',
        unit: 'kW',
        value: 2.45,
        dataQuality: 0,
        status: 'NORMAL',
        usageStatus: 'NORMAL',
        eventTime: null,
        receivedTime: null,
        reason: null,
      },
      {
        pointId: 'sp2',
        pointCode: 'VOLTAGE',
        pointName: '电压',
        unit: 'V',
        value: 220.5,
        dataQuality: 0,
        status: 'NORMAL',
        usageStatus: 'NORMAL',
        eventTime: null,
        receivedTime: null,
        reason: null,
      },
    ]

    const wrapper = mount(SinglePhaseMeterBoard, {
      props: {
        points: singlePoints,
        trendRecords: [],
        loading: false,
      },
      global: {
        stubs: {
          MeterRealtimeTrendChart: { template: '<div class="mock-chart">Chart</div>' },
          MeterPointReadingTable: { template: '<div class="mock-table">Table</div>' },
        },
      },
    })

    expect(wrapper.text()).toContain('实时用电功率')
    expect(wrapper.text()).toContain('工作电压')
    wrapper.unmount()
  })

  it('switches between ThreePhaseMeterBoard and SinglePhaseMeterBoard in MeterRealtimeBoard based on equipment', () => {
    const equipment3P: AssetEquipmentDetail = {
      equipmentId: 'eq-3p-1',
      equipmentCode: 'MTR_3P_01',
      equipmentName: '进线三相电表',
      typeCode: '3P_METER',
      category: 'METER',
      buildingId: 'b-1',
      buildingName: '试点大楼',
      spaceId: null,
      spaceName: null,
      systemGroupId: null,
      systemGroupName: null,
      productId: null,
      productName: null,
      status: 'ACTIVE',
      expectedProfileCode: null,
      lastDiscoveredTime: null,
      pointSummary: { total: 5, required: 0, configuredRequired: 0 },
      allowedActions: [],
      updateTime: 0,
      manufacturer: null,
      ratedCapacity: null,
      ratedPower: null,
      designCop: null,
      parameterGovernanceStatus: null,
      identities: [],
      references: { spaces: 0, systemGroups: 0, equipment: 0, points: 0, authorizations: 0, children: 0, aliases: 0, identities: 0 },
    }

    const wrapper = mount(MeterRealtimeBoard, {
      props: { equipment: equipment3P },
      global: {
        stubs: {
          ThreePhaseMeterBoard: { template: '<div class="three-phase-view">ThreePhaseBoard</div>' },
          SinglePhaseMeterBoard: { template: '<div class="single-phase-view">SinglePhaseBoard</div>' },
          ElSkeleton: { template: '<div>Loading...</div>' },
          ElAlert: { template: '<div>Alert</div>' },
          ElEmpty: { template: '<div>Empty</div>' },
        },
      },
    })

    expect(wrapper.find('.three-phase-view').exists()).toBe(true)
    expect(wrapper.find('.single-phase-view').exists()).toBe(false)
    wrapper.unmount()
  })

  it('initializes trend history and passes props down in MeterRealtimeBoard', async () => {
    const apiAssets = await import('../../api/assets')
    vi.mocked(apiAssets.getEquipmentTrendHistory).mockResolvedValueOnce({
      equipmentId: 'eq-3p-1',
      startTime: '2026-09-21T10:00:00Z',
      endTime: '2026-09-21T11:00:00Z',
      series: [
        {
          pointCode: 'P_TOTAL',
          pointName: '总有功功率',
          unit: 'kW',
          data: [[1700000000000, 32.5]],
        },
      ],
    })

    const equipment3P: AssetEquipmentDetail = {
      equipmentId: 'eq-3p-1',
      equipmentCode: 'MTR_3P_01',
      equipmentName: '进线三相电表',
      typeCode: '3P_METER',
      category: 'METER',
      buildingId: 'b-1',
      buildingName: '试点大楼',
      spaceId: null,
      spaceName: null,
      systemGroupId: null,
      systemGroupName: null,
      productId: null,
      productName: null,
      status: 'ACTIVE',
      expectedProfileCode: null,
      lastDiscoveredTime: null,
      pointSummary: { total: 5, required: 0, configuredRequired: 0 },
      allowedActions: [],
      updateTime: 0,
      manufacturer: null,
      ratedCapacity: null,
      ratedPower: null,
      designCop: null,
      parameterGovernanceStatus: null,
      identities: [],
      references: { spaces: 0, systemGroups: 0, equipment: 0, points: 0, authorizations: 0, children: 0, aliases: 0, identities: 0 },
    }

    const wrapper = mount(MeterRealtimeBoard, {
      props: { equipment: equipment3P },
      global: {
        stubs: {
          ThreePhaseMeterBoard: {
            props: ['points', 'trendRecords', 'historyLoading', 'apiPending', 'rangeType'],
            template: '<div class="three-phase-view" :data-range="rangeType" :data-records-len="trendRecords?.length ?? 0">ThreePhaseBoard</div>',
          },
          SinglePhaseMeterBoard: { template: '<div class="single-phase-view" />' },
          ElSkeleton: { template: '<div />' },
          ElAlert: { template: '<div />' },
          ElEmpty: { template: '<div />' },
        },
      },
    })

    await vi.waitFor(() => {
      const board = wrapper.find('.three-phase-view')
      expect(board.exists()).toBe(true)
      expect(board.attributes('data-records-len')).toBe('1')
    })

    expect(apiAssets.getEquipmentTrendHistory).toHaveBeenCalledWith(
      'eq-3p-1',
      expect.objectContaining({ intervalSeconds: 10 }),
    )
    wrapper.unmount()
  })

  it('supports zooming in and out of MeterRealtimeTrendChart', async () => {
    const wrapper = mount(MeterRealtimeTrendChart, {
      props: {
        phase: '3P',
        records: [
          { time: 1700000000000, power: 12.5, currentA: 20.1, currentB: 19.8, currentC: 20.3 },
          { time: 1700000010000, power: 13.2, currentA: 21.0, currentB: 20.5, currentC: 20.9 },
        ],
        loading: false,
      },
      global: {
        stubs: {
          ChartView: { template: '<div class="mock-chart-view" />' },
          ElDialog: {
            props: ['modelValue'],
            template: '<div v-if="modelValue" class="mock-dialog"><slot name="header" /><slot /></div>',
          },
          ElTooltip: { template: '<div><slot /></div>' },
          ElTag: { template: '<span class="mock-tag"><slot /></span>' },
          ElButton: {
            template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
          },
        },
      },
    })

    expect(wrapper.find('.mock-dialog').exists()).toBe(false)
    const zoomInBtn = wrapper.find('[data-test="btn-trend-zoom-in"]')
    expect(zoomInBtn.exists()).toBe(true)
    expect(zoomInBtn.text()).toContain('放大')

    await zoomInBtn.trigger('click')

    const dialog = wrapper.find('.mock-dialog')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('三相外机电表实时用电功率与三相电流走势大图')
    expect(dialog.text()).toContain('全屏放大模式')
    expect(dialog.text()).toContain('支持鼠标滚轮缩放与底部时间滑块自由拖拽')

    const zoomOutBtn = wrapper.find('[data-test="btn-trend-zoom-out"]')
    expect(zoomOutBtn.exists()).toBe(true)
    expect(zoomOutBtn.text()).toContain('还原小图')
    await zoomOutBtn.trigger('click')

    expect(wrapper.find('.mock-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders single-phase title and enlarged title properly', async () => {
    const wrapper = mount(MeterRealtimeTrendChart, {
      props: {
        phase: '1P',
        records: [
          { time: 1700000000000, power: 3.2, voltage: 220.5 },
        ],
        loading: false,
      },
      global: {
        stubs: {
          ChartView: { template: '<div class="mock-chart-view" />' },
          ElDialog: {
            props: ['modelValue'],
            template: '<div v-if="modelValue" class="mock-dialog"><slot name="header" /><slot /></div>',
          },
          ElTooltip: { template: '<div><slot /></div>' },
          ElTag: { template: '<span class="mock-tag"><slot /></span>' },
          ElButton: {
            template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('实时用电功率与电压走势')
    const zoomInBtn = wrapper.find('[data-test="btn-trend-zoom-in"]')
    await zoomInBtn.trigger('click')

    const dialog = wrapper.find('.mock-dialog')
    expect(dialog.exists()).toBe(true)
    expect(wrapper.text()).toContain('单相电表实时用电功率与电压走势大图')
    wrapper.unmount()
  })

  it('renders time range selector with presets and emits change-range', async () => {
    const wrapper = mount(MeterRealtimeTrendChart, {
      props: {
        phase: '3P',
        records: [],
        rangeType: 'realtime',
      },
      global: {
        stubs: {
          ChartView: { template: '<div class="mock-chart-view" />' },
          ElDialog: { template: '<div />' },
          ElTooltip: { template: '<div><slot /></div>' },
          ElTag: { template: '<span class="mock-tag"><slot /></span>' },
          ElButton: { template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
          ElRadioGroup: {
            props: ['modelValue'],
            template: '<div class="mock-radio-group"><slot /></div>',
          },
          ElRadioButton: {
            props: ['value'],
            template: '<button class="mock-radio-btn" :data-val="value" @click="$parent.$emit(\'change\', value)"><slot /></button>',
          },
          ElDatePicker: { template: '<div class="mock-date-picker" />' },
        },
      },
    })

    expect(wrapper.text()).toContain('时间范围')
    expect(wrapper.text()).toContain('实时追踪')
    expect(wrapper.text()).toContain('近6小时')
    expect(wrapper.text()).toContain('今日')
    expect(wrapper.text()).toContain('近24小时')
    expect(wrapper.text()).toContain('自定义')

    const radio6h = wrapper.find('[data-val="6h"]')
    expect(radio6h.exists()).toBe(true)
    await radio6h.trigger('click')

    expect(wrapper.emitted('change-range')).toBeTruthy()
    expect(wrapper.emitted('change-range')![0]).toEqual(['6h'])
    wrapper.unmount()
  })

  it('displays api pending badge and handles refresh button', async () => {
    const wrapper = mount(MeterRealtimeTrendChart, {
      props: {
        phase: '3P',
        records: [
          { time: 1700000000000, power: 10.5 },
        ],
        apiPending: true,
        historyLoading: false,
      },
      global: {
        stubs: {
          ChartView: { template: '<div class="mock-chart-view" />' },
          ElDialog: { template: '<div />' },
          ElTooltip: { template: '<div><slot /></div>' },
          ElTag: { template: '<span class="mock-tag"><slot /></span>' },
          ElButton: { template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
          ElRadioGroup: { template: '<div class="mock-radio-group"><slot /></div>' },
          ElRadioButton: { template: '<button><slot /></button>' },
          ElDatePicker: { template: '<div />' },
        },
      },
    })

    expect(wrapper.find('[data-test="badge-api-pending"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('后端历史时序接口建设中')

    const refreshBtn = wrapper.find('[data-test="btn-refresh-history"]')
    expect(refreshBtn.exists()).toBe(true)
    await refreshBtn.trigger('click')

    expect(wrapper.emitted('refresh-history')).toBeTruthy()
    wrapper.unmount()
  })
})
