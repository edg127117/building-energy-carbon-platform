import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { daikinApi } from '../api/daikin'
import { hvacControlApi } from '../api/hvac-control'
import DaikinMonitoringPanel from './DaikinMonitoringPanel.vue'
import type { HvacControlOperation } from '../models/hvac-control'
import DaikinDeviceDetail from './DaikinDeviceDetail.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

const controlAccess = vi.hoisted(() => ({ testOpenControls: { value: false }, loaded: { value: true }, refresh: vi.fn() }))

vi.mock('../api/hvac-control', () => ({
  hvacControlApi: { buildings: vi.fn() },
}))

vi.mock('../composables/use-hvac-control-access', () => ({
  useHvacControlAccess: () => controlAccess,
}))

vi.mock('../api/daikin', () => ({
  newDaikinFastWatchId: () => 'watch-test-1234',
  daikinApi: {
    spaces: vi.fn(),
    devices: vi.fn(),
    current: vi.fn(),
    events: vi.fn(),
    temperature: vi.fn(),
    history: vi.fn(),
    observedRuntime: vi.fn(),
    exceptions: vi.fn(),
    startFastWatch: vi.fn().mockResolvedValue({ enabled: false, intervalMillis: 5000, expiresAt: 0 }),
    stopFastWatch: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('./HvacDeviceGrantPanel.vue', () => ({
  default: { name: 'HvacDeviceGrantPanel', template: '<section data-testid="device-grant-panel" />' },
}))

describe('DaikinMonitoringPanel', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(daikinApi.startFastWatch).mockResolvedValue({ enabled: false, intervalMillis: 5000, expiresAt: 0 })
    vi.mocked(daikinApi.stopFastWatch).mockResolvedValue(true)
    controlAccess.testOpenControls.value = false
    controlAccess.loaded.value = true
    vi.mocked(hvacControlApi.buildings).mockResolvedValue([
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

  const completedForMonitor = (): HvacControlOperation => ({
    operationId: 'OP-1', equipmentId: 'eq-idu-2', action: 'ON_OFF', value: 'off', status: 'CLOUD_VERIFIED',
    reasonCode: 'FAST_TARGET_READBACK_MATCH', vendorCode: '10000', observedValue: 'off',
    createdAt: 1, updatedAt: 2, closedReason: null, deadlineAt: 120001, stableCount: 0,
    stableSince: null, lastReadAt: 1_700_000_010_000, unitStatus: 'stopped', isolated: false, cloudStable: false, verificationPolicy: 'TEST_FAST_V1',
  })
  async function openMonitorForRetry() {
    vi.useFakeTimers()
    const wrapper = mount(DaikinMonitoringPanel, { global: { stubs: {
      ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue" class="drawer-stub"><slot /></div>' },
      ChartView: true,
    } } })
    await flushPromises()
    await wrapper.find('.device-card').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('详情关注静默同步最新状态和事件当前页，关闭后停止读取', async () => {
    vi.mocked(daikinApi.startFastWatch).mockResolvedValue({ enabled: true, intervalMillis: 5000, expiresAt: Date.now() + 15000 })
    vi.mocked(daikinApi.events).mockResolvedValue({ items: [], nextCursor: 'older-page' })
    const wrapper = await openMonitorForRetry()
    await wrapper.find('#tab-events').trigger('click')
    await flushPromises()
    wrapper.find('.events-toolbar').findComponent({ name: 'ElRadioGroup' }).vm.$emit('change', '24H')
    await flushPromises()
    vi.mocked(daikinApi.events).mockImplementation(async () => ({ items: [{
      eventId: 1, fieldName: 'onOff', beforeNormalizedValue: 'on', afterNormalizedValue: 'off',
      previousObservedAt: Date.now() - 5000, observedAt: Date.now() + 1000, afterGap: false,
    }], nextCursor: 'older-page' }))
    const currentCalls = vi.mocked(daikinApi.current).mock.calls.length
    const eventCalls = vi.mocked(daikinApi.events).mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(daikinApi.current).toHaveBeenCalledTimes(currentCalls + 1)
    expect(daikinApi.events).toHaveBeenCalledTimes(eventCalls + 1)
    expect(daikinApi.events).toHaveBeenLastCalledWith('eq-idu-2', undefined)
    expect(wrapper.find('.event-card').exists()).toBe(true)
    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    const earlier = wrapper.findAll('button').find(button => button.text() === '更早记录')
    expect(earlier).toBeDefined()
    await earlier!.trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(daikinApi.events).toHaveBeenLastCalledWith('eq-idu-2', 'older-page')
    wrapper.findComponent(DaikinDeviceDetail).vm.$emit('close')
    await flushPromises()
    const countAfterClose = vi.mocked(daikinApi.current).mock.calls.length
    await vi.advanceTimersByTimeAsync(10000)
    expect(daikinApi.current).toHaveBeenCalledTimes(countAfterClose)
    expect(daikinApi.stopFastWatch).toHaveBeenCalledWith('eq-idu-2', 'watch-test-1234')
    wrapper.unmount()
  })

  it('控制完成先重读一次列表，后续十秒同步平台状态而不回填请求目标', async () => {
    const wrapper = await openMonitorForRetry()
    const original = await vi.mocked(daikinApi.devices).mock.results[0]!.value
    const fresh = { ...original, items: original.items.map((device: typeof original.items[number]) => device.deviceKind === 'INDOOR'
      ? { ...device, lastValidAt: 1_700_000_011_000, onOff: { ...device.onOff!, value: 'off' }, unitStatus: { ...device.unitStatus!, value: 'stopped' } }
      : device) }
    vi.mocked(daikinApi.devices).mockResolvedValue(fresh).mockResolvedValueOnce(original)
    wrapper.findComponent(DaikinDeviceDetail).vm.$emit('controlCompleted', completedForMonitor())
    await flushPromises()
    expect(wrapper.find('.device-card-running').exists()).toBe(true)
    expect(daikinApi.devices).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(wrapper.find('.device-card-running').exists()).toBe(true)
    expect(daikinApi.devices).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(daikinApi.devices).toHaveBeenCalledTimes(4)
    expect(wrapper.find('.device-card-running').exists()).toBe(false)
    wrapper.unmount()
  })

  it('关闭抽屉后保留整页十秒刷新，不再读取详情或续租关注', async () => {
    const wrapper = await openMonitorForRetry()
    wrapper.findComponent(DaikinDeviceDetail).vm.$emit('controlCompleted', completedForMonitor())
    await flushPromises()
    const calls = vi.mocked(daikinApi.devices).mock.calls.length
    wrapper.findComponent(DaikinDeviceDetail).vm.$emit('close')
    await flushPromises()
    const currentCalls = vi.mocked(daikinApi.current).mock.calls.length
    const watchCalls = vi.mocked(daikinApi.startFastWatch).mock.calls.length
    await vi.advanceTimersByTimeAsync(15_000)
    expect(daikinApi.devices).toHaveBeenCalledTimes(calls + 1)
    expect(daikinApi.current).toHaveBeenCalledTimes(currentCalls)
    expect(daikinApi.startFastWatch).toHaveBeenCalledTimes(watchCalls)
    wrapper.unmount()
  })

  it('列表刷新失败保留旧卡片，下个十秒轮次恢复而不重发控制', async () => {
    const wrapper = await openMonitorForRetry()
    vi.mocked(daikinApi.devices).mockRejectedValueOnce(new Error('offline read failed'))
    wrapper.findComponent(DaikinDeviceDetail).vm.$emit('controlCompleted', completedForMonitor())
    await flushPromises()
    expect(wrapper.find('.device-card-running').exists()).toBe(true)
    expect(wrapper.find('.device-list').text()).toContain('请求失败')
    await vi.advanceTimersByTimeAsync(10_000)
    const calls = vi.mocked(daikinApi.devices).mock.calls.length
    expect(calls).toBe(3)
    expect(wrapper.find('.device-list').text()).not.toContain('请求失败')
    wrapper.unmount()
  })

  it('十秒刷新卡片、运行数量和空间汇总，温度及其他卡片补全仍按分钟读取', async () => {
    vi.useFakeTimers()
    const wrapper = mount(DaikinMonitoringPanel, { global: { stubs: { ChartView: true } } })
    await flushPromises()
    const original = await vi.mocked(daikinApi.devices).mock.results[0]!.value
    vi.mocked(daikinApi.devices).mockResolvedValue({ ...original, items: original.items.map(device => device.deviceKind === 'INDOOR'
      ? { ...device, onOff: { ...device.onOff!, value: 'off' }, unitStatus: { ...device.unitStatus!, value: 'stopped' } }
      : device) })
    const temperatures = vi.mocked(daikinApi.temperature).mock.calls.length
    const currents = vi.mocked(daikinApi.current).mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(wrapper.text()).toContain('运行中（0）')
    expect(wrapper.find('.space-summary-chip').text()).toContain('在运 0 台')
    expect(wrapper.find('.device-card-running').exists()).toBe(false)
    expect(daikinApi.devices).toHaveBeenCalledTimes(2)
    expect(daikinApi.temperature).toHaveBeenCalledTimes(temperatures)
    expect(daikinApi.current).toHaveBeenCalledTimes(currents)
    expect(daikinApi.startFastWatch).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(50_000)
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(7)
    expect(daikinApi.temperature).toHaveBeenCalledTimes(temperatures * 2)
    expect(daikinApi.current).toHaveBeenCalledTimes(currents * 2)
    wrapper.unmount()
  })

  it('详情五秒核验后同步卡片和汇总，不等待整页轮询且不增加温度请求', async () => {
    vi.mocked(daikinApi.startFastWatch).mockResolvedValue({ enabled: true, intervalMillis: 5000, expiresAt: Date.now() + 15000 })
    const wrapper = await openMonitorForRetry()
    const original = await vi.mocked(daikinApi.devices).mock.results[0]!.value
    const snapshot = await vi.mocked(daikinApi.current).mock.results[0]!.value
    vi.mocked(daikinApi.devices).mockResolvedValue({ ...original, items: original.items.map(device => device.deviceKind === 'INDOOR'
      ? { ...device, onOff: { ...device.onOff!, value: 'off' }, unitStatus: { ...device.unitStatus!, value: 'stopped' } }
      : device) })
    vi.mocked(daikinApi.current).mockResolvedValue({ ...snapshot, lastValidAt: 1700000005000, fields: snapshot.fields.map(field => ({
      ...field, normalizedValue: field.fieldName === 'fanSpeed' ? 'low' : field.fieldName === 'onOff' ? 'off' : field.fieldName === 'unitStatus' ? 'stopped' : field.normalizedValue,
    })) })
    const temperatures = vi.mocked(daikinApi.temperature).mock.calls.length
    const lists = vi.mocked(daikinApi.devices).mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 1)
    expect(daikinApi.temperature).toHaveBeenCalledTimes(temperatures)
    expect(wrapper.find('.device-card').text()).toContain('制冷 · 低档')
    expect(wrapper.find('.device-card-running').exists()).toBe(false)
    expect(wrapper.text()).toContain('运行中（0）')
    expect(wrapper.find('.space-summary-chip').text()).toContain('在运 0 台')
    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    wrapper.unmount()
  })

  it('慢列表请求期间合并重复详情通知，完成后补读一次且保留抽屉', async () => {
    const wrapper = await openMonitorForRetry()
    const original = await vi.mocked(daikinApi.devices).mock.results[0]!.value
    const snapshot = await vi.mocked(daikinApi.current).mock.results[0]!.value
    let finish!: (value: typeof original) => void
    vi.mocked(daikinApi.devices).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const lists = vi.mocked(daikinApi.devices).mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    const detail = wrapper.findComponent(DaikinDeviceDetail)
    detail.vm.$emit('stateRefreshed', snapshot)
    detail.vm.$emit('stateRefreshed', snapshot)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 1)
    finish(original)
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 2)
    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    detail.vm.$emit('stateRefreshed', { ...snapshot, equipmentId: 'another-device' })
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 2)
    wrapper.unmount()
  })

  it('状态变化查询缓慢时，详情当前状态仍立即通知整页同步', async () => {
    vi.mocked(daikinApi.startFastWatch).mockResolvedValue({ enabled: true, intervalMillis: 5000, expiresAt: Date.now() + 15000 })
    vi.mocked(daikinApi.events).mockResolvedValue({ items: [], nextCursor: null })
    const wrapper = await openMonitorForRetry()
    await wrapper.find('#tab-events').trigger('click')
    await flushPromises()
    let finish!: (value: Awaited<ReturnType<typeof daikinApi.events>>) => void
    vi.mocked(daikinApi.events).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const lists = vi.mocked(daikinApi.devices).mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 1)
    finish({ items: [], nextCursor: null })
    await flushPromises()
    wrapper.unmount()
  })

  it('后台暂停整页读取，返回可见立即补读，卸载后不保留监听或定时器', async () => {
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const wrapper = await openMonitorForRetry()
    const lists = vi.mocked(daikinApi.devices).mock.calls.length
    hidden.mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists)
    hidden.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 1)
    wrapper.unmount()
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(daikinApi.devices).toHaveBeenCalledTimes(lists + 1)
  })

  it('温度补全在途时不重复请求，迟到的旧状态不覆盖详情已取得的新风速', async () => {
    vi.useFakeTimers()
    let finish!: (value: Awaited<ReturnType<typeof daikinApi.temperature>>) => void
    const pending = new Promise<Awaited<ReturnType<typeof daikinApi.temperature>>>(resolve => { finish = resolve })
    vi.mocked(daikinApi.temperature).mockReturnValue(pending)
    const wrapper = mount(DaikinMonitoringPanel, { global: { stubs: {
      ChartView: true,
      DaikinDeviceDetail: { name: 'DaikinDeviceDetail', emits: ['stateRefreshed'], template: '<div />' },
    } } })
    await flushPromises()
    const snapshot = await vi.mocked(daikinApi.current).mock.results[0]!.value
    const temperatures = vi.mocked(daikinApi.temperature).mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(daikinApi.temperature).toHaveBeenCalledTimes(temperatures)
    await wrapper.find('.device-card').trigger('click')
    wrapper.findComponent({ name: 'DaikinDeviceDetail' }).vm.$emit('stateRefreshed', {
      ...snapshot, lastValidAt: 1700000005000,
      fields: snapshot.fields.map(field => field.fieldName === 'fanSpeed' ? { ...field, normalizedValue: 'low' } : field),
    })
    await flushPromises()
    expect(wrapper.find('.device-card').text()).toContain('制冷 · 低档')
    finish({ fieldStatus: 'PRESENT', unit: '°C', reading: {
      observedAt: 1700000000000, value: 24, dataQuality: 0, gapBefore: false, stale: false,
    } })
    await flushPromises()
    expect(wrapper.find('.device-card').text()).toContain('制冷 · 低档')
    expect(wrapper.find('.device-card').text()).toContain('24°C')
    wrapper.unmount()
  })

  it('控制完成静默重读监测卡片和详情，保持抽屉且不把请求目标直接写入监测', async () => {
    const wrapper = mount(DaikinMonitoringPanel, {
      global: { stubs: {
        ElDrawer: { props: ['modelValue', 'title'], template: '<div v-if="modelValue" class="drawer-stub"><h2>{{ title }}</h2><slot /></div>' },
        ChartView: true,
        HvacControlPanel: { name: 'HvacControlPanel', props: ['equipmentId', 'refreshTick'], emits: ['completed'], template: '<section data-testid="control-stub" />' },
      } },
    })
    await flushPromises()
    const initial = await vi.mocked(daikinApi.devices).mock.results[0]!.value
    const originalCurrent = await vi.mocked(daikinApi.current).mock.results[0]!.value
    await wrapper.find('.device-card').trigger('click')
    await flushPromises()
    await wrapper.find('#tab-control').trigger('click')
    await flushPromises()
    const panel = wrapper.findComponent({ name: 'HvacControlPanel' })
    const completed: HvacControlOperation = {
      operationId: 'OP-1', equipmentId: 'eq-idu-2', action: 'ON_OFF', value: 'off', status: 'CLOUD_VERIFIED',
      reasonCode: 'FAST_TARGET_READBACK_MATCH', vendorCode: '10000', observedValue: 'off',
      createdAt: 1, updatedAt: 2, closedReason: null, deadlineAt: 120001, stableCount: 0,
      stableSince: null, lastReadAt: 2, unitStatus: 'stopped', isolated: false, cloudStable: false, verificationPolicy: 'TEST_FAST_V1',
    }
    const listCalls = vi.mocked(daikinApi.devices).mock.calls.length
    const detailCalls = vi.mocked(daikinApi.current).mock.calls.length
    panel.vm.$emit('completed', { ...completed, equipmentId: 'another-device' })
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(listCalls)
    expect(daikinApi.current).toHaveBeenCalledTimes(detailCalls)
    let finishList!: (value: typeof initial) => void
    vi.mocked(daikinApi.devices).mockImplementationOnce(() => new Promise(resolve => { finishList = resolve }))
    vi.mocked(daikinApi.current).mockResolvedValue({ ...originalCurrent, fields: originalCurrent.fields.map((field: typeof originalCurrent.fields[number]) =>
      field.fieldName === 'onOff' ? { ...field, normalizedValue: 'off', rawJson: '"off"' } : field) })
    const temperatureCalls = vi.mocked(daikinApi.temperature).mock.calls.length
    panel.vm.$emit('completed', completed)
    await flushPromises()
    expect(daikinApi.devices).toHaveBeenCalledTimes(listCalls + 1)
    expect(daikinApi.current).toHaveBeenCalledTimes(detailCalls + 1)
    expect(daikinApi.temperature).toHaveBeenCalledTimes(temperatureCalls + 2)
    expect(wrapper.find('.device-card-running').exists()).toBe(true)
    expect(wrapper.find('.drawer-stub').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'HvacControlPanel' }).props('refreshTick')).toBe(0)
    finishList({ ...initial, items: initial.items.map((device: typeof initial.items[number]) => device.deviceKind === 'INDOOR'
      ? { ...device, onOff: { ...device.onOff!, value: 'off' }, unitStatus: { ...device.unitStatus!, value: 'stopped' } }
      : device) })
    await flushPromises()
    expect(wrapper.find('.device-card-running').exists()).toBe(false)
    expect(wrapper.find('.device-card').text()).toContain('已关机')
    expect(wrapper.find('.device-card').text()).toContain('25°C')
    expect(wrapper.find('.drawer-stub').text()).toContain('关')
    wrapper.unmount()
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
    expect(wrapper.find('[data-testid="device-grant-panel"]').exists()).toBe(true)
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

  it('测试开放时使用控制建筑源并隐藏逐台批量许可面板', async () => {
    controlAccess.testOpenControls.value = true
    const wrapper = mount(DaikinMonitoringPanel, {
      global: { stubs: { ChartView: true } },
    })
    await flushPromises()

    expect(hvacControlApi.buildings).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="device-grant-panel"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('测试控制已开放')
    expect(wrapper.text()).toContain('无需逐台配置')
    wrapper.unmount()
  })

  it('全选当前筛选会读取跨页完整范围后再应用快捷状态，不把当前页伪装成全选', async () => {
    const runningIndexes = new Set([1, 101, 150])
    const allDevices = Array.from({ length: 150 }, (_, index) => {
      const item = index + 1
      const running = runningIndexes.has(item)
      return {
        identityId: `id-${item}`,
        equipmentId: `eq-${String(item).padStart(3, '0')}`,
        pendingId: `pd-${item}`,
        buildingId: 'BLD001',
        spaceId: 'SP-308',
        systemGroupId: 'SYS-3F',
        deviceKind: 'INDOOR',
        mappingVersion: 1,
        active: true,
        stale: false,
        lastValidAt: 1_700_000_000_000,
        onOff: { value: running ? 'on' : 'off', status: 'PRESENT', lastValidAt: 1_700_000_000_000, stale: false },
        mode: { value: 'cooling', status: 'PRESENT', lastValidAt: 1_700_000_000_000, stale: false },
        unitStatus: { value: running ? 'operating' : 'stopped', status: 'PRESENT', lastValidAt: 1_700_000_000_000, stale: false },
        hasActiveException: false,
        equipmentCode: `IDU${item}`,
        equipmentName: `测试内机 ${item}`,
      }
    })
    vi.mocked(daikinApi.devices).mockImplementation(async (_building, currentPage, _kind, _keyword, _spaceId, _state, _hasException, size = 20) => ({
      total: allDevices.length,
      items: allDevices.slice((currentPage - 1) * size, currentPage * size),
    }))
    const wrapper = mount(DaikinMonitoringPanel, {
      global: { stubs: {
        ChartView: true,
        HvacBatchControlPanel: {
          name: 'HvacBatchControlPanel',
          props: ['equipmentIds', 'devices'],
          template: '<section data-testid="batch-panel-stub">{{ equipmentIds.join(",") }}</section>',
        },
      } },
    })
    await flushPromises()

    const runningPill = wrapper.findAll('.status-pill').find(button => button.text().includes('运行中'))!
    await runningPill.trigger('click')
    await wrapper.find('[data-testid="batch-select-filtered"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(daikinApi.devices).toHaveBeenCalledWith('BLD001', 1, '', '', '', '', undefined, 100)
    expect(daikinApi.devices).toHaveBeenCalledWith('BLD001', 2, '', '', '', '', undefined, 100)
    const batchPanel = wrapper.findComponent({ name: 'HvacBatchControlPanel' })
    expect(batchPanel.props('equipmentIds')).toEqual(['eq-001', 'eq-101', 'eq-150'])
    expect(batchPanel.props('devices')).toHaveLength(3)
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
