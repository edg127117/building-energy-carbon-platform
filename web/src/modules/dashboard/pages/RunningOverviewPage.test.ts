import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listAccessibleBuildings } from '../api/hvac'
import { daikinApi } from '../api/daikin'
import RunningOverviewPage from './RunningOverviewPage.vue'

const pushMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => ({ path: '/operations/overview/running', query: {} }),
}))

vi.mock('../api/hvac', async original => ({
  ...(await original<typeof import('../api/hvac')>()),
  listAccessibleBuildings: vi.fn(),
}))

vi.mock('../api/daikin', () => ({
  daikinApi: {
    spaces: vi.fn(),
    devices: vi.fn(),
    current: vi.fn(),
    events: vi.fn(),
    temperature: vi.fn(),
    history: vi.fn(),
    observedRuntime: vi.fn(),
    exceptions: vi.fn(),
  },
}))

vi.mock('@/modules/asset-management/public', async original => {
  const actual = await original<typeof import('@/modules/asset-management/public')>()
  return {
    ...actual,
    useAssetManagement: () => ({
      equipment: ref({
        total: 2,
        pageNo: 1,
        pageSize: 100,
        items: [
          {
            equipmentId: 'EQ-METER-308',
            equipmentCode: 'METER-308',
            equipmentName: '308空调外机电表',
            buildingId: 'BLD001',
            buildingName: '测试大楼',
            spaceId: 'SP-308',
            spaceName: '308室',
            systemGroupId: 'SYS-POWER',
            systemGroupName: '动力配电回路',
            typeCode: 'OUTDOOR_UNIT_METER_339',
            expectedProfileCode: 'OUTDOOR_UNIT_METER_339',
            ratedCapacity: null,
            ratedPower: 15,
            designCop: null,
            status: 1,
          },
          {
            equipmentId: 'EQ-WCR-1',
            equipmentCode: 'WCR-1',
            equipmentName: '1号冷水机组',
            buildingId: 'BLD001',
            buildingName: '测试大楼',
            spaceId: 'SP-B1',
            spaceName: 'B1制冷机房',
            systemGroupId: 'SYS-COLD',
            systemGroupName: '制冷站系统',
            typeCode: 'WCR',
            expectedProfileCode: null,
            ratedCapacity: 1200,
            ratedPower: 260,
            designCop: 5.2,
            status: 1,
          },
        ],
      }),
      initialize: vi.fn().mockResolvedValue(undefined),
      setEquipmentQuery: vi.fn().mockResolvedValue(undefined),
    }),
  }
})

describe('RunningOverviewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listAccessibleBuildings).mockResolvedValue([
      { buildingId: 'BLD001', buildingName: '测试大楼' },
    ] as never)
    vi.mocked(daikinApi.spaces).mockResolvedValue([
      { spaceId: 'SP-308', spaceName: '3F B308办公区' },
      { spaceId: 'SP-307', spaceName: '3F B307会议室' },
    ])
    vi.mocked(daikinApi.devices).mockResolvedValue({
      total: 2,
      items: [
        {
          identityId: 'id-idu-308',
          equipmentId: 'eq-idu-308',
          pendingId: 'pd-idu-308',
          buildingId: 'BLD001',
          spaceId: 'SP-308',
          systemGroupId: 'SYS-3F',
          deviceKind: 'INDOOR',
          mappingVersion: 1,
          active: true,
          stale: false,
          lastValidAt: 1700000000000,
          onOff: { value: 'on', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          mode: { value: 'cooling', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          unitStatus: { value: 'operating', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          hasActiveException: false,
          equipmentCode: 'IDU-308-1',
          equipmentName: '大金内机-B308-1',
        },
        {
          identityId: 'id-idu-307',
          equipmentId: 'eq-idu-307',
          pendingId: 'pd-idu-307',
          buildingId: 'BLD001',
          spaceId: 'SP-307',
          systemGroupId: 'SYS-3F',
          deviceKind: 'INDOOR',
          mappingVersion: 1,
          active: true,
          stale: false,
          lastValidAt: 1700000000000,
          onOff: { value: 'on', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          mode: { value: 'cooling', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          unitStatus: { value: 'operating', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          hasActiveException: false,
          equipmentCode: 'IDU-307-1',
          equipmentName: '大金内机-B307-1',
        },
      ],
    })
    vi.mocked(daikinApi.current).mockResolvedValue({
      equipmentId: 'eq-idu-308',
      active: true,
      lastValidAt: 1700000000000,
      fields: [
        {
          fieldName: 'fanSpeed',
          rawJson: '"high"',
          normalizedValue: 'high',
          status: 'PRESENT',
          lastValidAt: 1700000000000,
          lastAttemptAt: 1700000000000,
          lastAttemptRawJson: null,
          valueVisible: true,
          stale: false,
          mappingVersion: 1,
          lastAttemptMappingVersion: 1,
        },
      ],
    })
    vi.mocked(daikinApi.temperature).mockImplementation(async (id, field) => ({
      fieldStatus: 'PRESENT',
      unit: '°C',
      reading: {
        observedAt: 1700000000000,
        value: id === 'eq-idu-307' && field === 'roomTemp' ? 27.2 : 24.0,
        dataQuality: 0,
        gapBefore: false,
        stale: false,
      },
    }))
    vi.mocked(daikinApi.observedRuntime).mockResolvedValue({
      source: 'PLATFORM_OBSERVED',
      granularity: 'DAY',
      statisticsZone: 'Asia/Shanghai',
      items: [
        {
          periodStart: 1700000000000,
          periodEnd: 1700086400000,
          elapsedMillis: 28800000,
          onMillis: 23400000,
          coveredMillis: 28800000,
        },
      ],
      nextCursor: null,
    })
  })

  it('renders V4 3-tier layout, filters right device table by left space bar, and switches subsystems', async () => {
    const wrapper = mount(RunningOverviewPage, {
      global: {
        stubs: {
          DaikinDeviceDetail: {
            props: ['equipmentId', 'visible'],
            template: '<div v-if="visible" class="daikin-drawer-stub">{{ equipmentId }}</div>',
          },
          MeterRealtimeBoard: {
            props: ['equipmentId'],
            template: '<div class="meter-board-stub">{{ equipmentId }}</div>',
          },
          ElDrawer: {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" class="el-drawer-stub"><h3>{{ title }}</h3><slot /></div>',
          },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('综合运行总览')
    expect(wrapper.text()).toContain('建筑综合能耗概览')
    expect(wrapper.text()).toContain('建筑碳排放概览')
    expect(wrapper.text()).toContain('分项用能设备结构占比')
    expect(wrapper.text()).toContain('全局设备运行态势')
    expect(wrapper.text()).toContain('各空间累计开机时长与室内均温强度分析')
    expect(wrapper.text()).toContain('单设备实时运行与设定温度达标巡检列表')
    expect(wrapper.text()).toContain('大金内机-B308-1')
    expect(wrapper.text()).toContain('大金内机-B307-1')
    expect(wrapper.text()).toContain('温差较大 (+3.2°C)')

    const spaceButtons = wrapper.findAll('.combo-col-btn')
    expect(spaceButtons.length).toBe(2)
    await spaceButtons[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('清除空间筛选')

    const powerTab = wrapper.findAll('.subsystem-tab').find(btn => btn.text().includes('供配电与表计'))
    await powerTab?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('各区域表计配置与三相主回路覆盖分析')
    expect(wrapper.text()).toContain('单表计实时监测与回路归属巡检列表')
    expect(wrapper.text()).toContain('308空调外机电表')

    const viewTrendBtn = wrapper.findAll('button').find(btn => btn.text().includes('查看走势'))
    await viewTrendBtn?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.meter-board-stub').exists()).toBe(true)
    expect(wrapper.find('.meter-board-stub').text()).toBe('EQ-METER-308')

    const lightingTab = wrapper.findAll('.subsystem-tab').find(btn => btn.text().includes('照明与插座'))
    await lightingTab?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('该建筑子系统暂未接入现场设备')
    wrapper.unmount()
  })
})
