import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { AssetEquipmentDetail, AssetPointReading } from '../../models/assets'
import MeterPointReadingTable from './MeterPointReadingTable.vue'
import ThreePhaseMeterBoard from './ThreePhaseMeterBoard.vue'
import SinglePhaseMeterBoard from './SinglePhaseMeterBoard.vue'
import MeterRealtimeBoard from './MeterRealtimeBoard.vue'

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
})
