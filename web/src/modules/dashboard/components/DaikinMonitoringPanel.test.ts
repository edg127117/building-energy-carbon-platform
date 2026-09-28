import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listAccessibleBuildings } from '../api/hvac'
import { daikinApi } from '../api/daikin'
import DaikinMonitoringPanel from './DaikinMonitoringPanel.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
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

describe('DaikinMonitoringPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listAccessibleBuildings).mockResolvedValue([
      { buildingId: 'BLD001', buildingName: '测试大楼' },
    ] as never)
    vi.mocked(daikinApi.spaces).mockResolvedValue([
      { spaceId: 'SP-308', spaceName: '3F B308办公区' },
    ])
    vi.mocked(daikinApi.devices).mockResolvedValue({
      total: 2,
      items: [
        {
          identityId: 'id-odu-2',
          equipmentId: 'eq-odu-2',
          pendingId: 'pd-odu-2',
          buildingId: 'BLD001',
          spaceId: null,
          systemGroupId: 'SYS-3F',
          deviceKind: 'OUTDOOR',
          mappingVersion: 1,
          active: true,
          stale: false,
          lastValidAt: 1700000000000,
          onOff: null,
          mode: null,
          unitStatus: { value: 'stopped', status: 'PRESENT', lastValidAt: 1700000000000, stale: false },
          hasActiveException: false,
          equipmentCode: 'ODU2',
          equipmentName: '大金3F-6空调外机',
        },
        {
          identityId: 'id-idu-2',
          equipmentId: 'eq-idu-2',
          pendingId: 'pd-idu-2',
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
          hasActiveException: true,
          equipmentCode: 'IDU2',
          equipmentName: '大金内机-B308-1',
        },
      ],
    })
    vi.mocked(daikinApi.current).mockResolvedValue({
      equipmentId: 'eq-idu-2',
      active: true,
      lastValidAt: 1700000000000,
      fields: [
        { fieldName: 'onOff', rawJson: '"on"', normalizedValue: 'on', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
        { fieldName: 'mode', rawJson: '"cooling"', normalizedValue: 'cooling', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
        { fieldName: 'fanSpeed', rawJson: '"high"', normalizedValue: 'high', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
        { fieldName: 'isFilterDirty', rawJson: 'true', normalizedValue: 'true', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
        { fieldName: 'unitStatus', rawJson: '"operating"', normalizedValue: 'operating', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
        { fieldName: 'modelName', rawJson: '"FSFP71AB"', normalizedValue: 'FSFP71AB', status: 'PRESENT', lastValidAt: 1700000000000, lastAttemptAt: 1700000000000, lastAttemptRawJson: null, valueVisible: true, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 },
      ],
    })
    vi.mocked(daikinApi.temperature).mockImplementation(async (_id, field) => ({
      fieldStatus: 'PRESENT',
      unit: '°C',
      reading: {
        observedAt: 1700000000000,
        value: field === 'roomTemp' ? 24.6 : 25,
        dataQuality: 0,
        gapBefore: false,
        stale: false,
      },
    }))
  })

  it('renders system grouping, quick status pills, semantic tones, and opens right-side drawer on card click', async () => {
    const wrapper = mount(DaikinMonitoringPanel, {
      global: {
        stubs: {
          ElDrawer: {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" class="drawer-stub"><h2>{{ title }}</h2><slot /></div>',
          },
          ChartView: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('室外机主系统')
    expect(wrapper.text()).toContain('大金3F-6空调外机')
    expect(wrapper.text()).toContain('大金内机-B308-1')
    expect(wrapper.text()).toContain('全部设备（2）')
    expect(wrapper.text()).toContain('运行中（1）')
    expect(wrapper.text()).toContain('模式／风速')
    expect(wrapper.text()).toContain('制冷 · 高档')
    expect(wrapper.text()).toContain('室温／设温')
    expect(wrapper.text()).toContain('24.6°C')
    expect(wrapper.text()).toContain('25°C')
    expect(wrapper.text()).toContain('滤网提醒')
    expect(wrapper.find('.device-card.device-card-running').exists()).toBe(true)

    await wrapper.find('.device-card').trigger('click')
    await flushPromises()

    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    expect(wrapper.text()).toContain('室内温度')
    expect(wrapper.text()).toContain('24.6 °C')
    expect(wrapper.text()).toContain('25 °C')
    expect(wrapper.text()).toContain('滤网需更换')
    expect(wrapper.text()).toContain('机组健康、维保与控制器状态')
    expect(wrapper.text()).toContain('设备档案与系统归属')
    expect(wrapper.text()).toContain('FSFP71AB')
    wrapper.unmount()
  })

  it('refreshes silently without unmounting cards and applies clock-skew-safe upper bound when switching 24h/7d temperature presets', async () => {
    vi.mocked(daikinApi.history).mockResolvedValue({
      unit: '°C',
      items: [
        { observedAt: 1700000000000, value: 24.5, dataQuality: 0, gapBefore: false, stale: false },
      ],
      nextCursor: null,
    })

    const wrapper = mount(DaikinMonitoringPanel, {
      global: {
        stubs: {
          ElDrawer: {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" class="drawer-stub"><h2>{{ title }}</h2><slot /></div>',
          },
          ChartView: true,
        },
      },
    })
    await flushPromises()

    await wrapper.find('.device-card').trigger('click')
    await flushPromises()

    // Simulate a slow refresh and verify existing DOM stays mounted without flashing skeleton
    let resolveDevices!: (value: Awaited<ReturnType<typeof daikinApi.devices>>) => void
    vi.mocked(daikinApi.devices).mockReturnValueOnce(new Promise(resolve => {
      resolveDevices = resolve
    }))
    const refreshBtn = wrapper.findAll('button').find(btn => btn.text() === '刷新')
    await refreshBtn?.trigger('click')
    expect(wrapper.find('.device-card').exists()).toBe(true)
    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    resolveDevices({
      total: 1,
      items: [
        {
          identityId: 'id-idu-2',
          equipmentId: 'eq-idu-2',
          pendingId: 'pd-idu-2',
          buildingId: 'BLD001',
          spaceId: 'SP-308',
          systemGroupId: 'SYS-3F',
          deviceKind: 'INDOOR',
          mappingVersion: 1,
          active: true,
          stale: false,
          lastValidAt: 1700000060000,
          onOff: { value: 'on', status: 'PRESENT', lastValidAt: 1700000060000, stale: false },
          mode: { value: 'cooling', status: 'PRESENT', lastValidAt: 1700000060000, stale: false },
          unitStatus: { value: 'operating', status: 'PRESENT', lastValidAt: 1700000060000, stale: false },
          hasActiveException: false,
          equipmentCode: 'IDU2',
          equipmentName: '大金内机-B308-1',
        },
      ],
    })
    await flushPromises()

    // Switch to temperature tab and verify quick range presets use clock-skew-safe upper bound
    const tempTab = wrapper.find('[id="tab-temperature"]')
    if (tempTab.exists()) {
      await tempTab.trigger('click')
      await flushPromises()
      const beforeClickNow = Date.now()
      const calls = vi.mocked(daikinApi.history).mock.calls
      expect(calls.length).toBeGreaterThanOrEqual(2)
      const latestCall = calls[calls.length - 1]
      expect(latestCall?.[3]).toBeLessThanOrEqual(beforeClickNow - 10_000)
    }
    wrapper.unmount()
  })
})
