import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInputNumber, ElPagination } from '@/shared/ui'
import type { HvacControlOperation, HvacControlOperationStatus } from '../models/hvac-control'

const api = vi.hoisted(() => ({
  options: vi.fn(), createOperation: vi.fn(), operation: vi.fn(), operations: vi.fn(), recheck: vi.fn(), close: vi.fn(), confirm: vi.fn(), release: vi.fn(),
}))
vi.mock('../api/hvac-control', () => ({
  hvacControlApi: api,
  newHvacControlIdempotencyKey: () => 'component-control-key',
}))
vi.mock('./HvacRecoveryPanel.vue', () => ({
  default: { name: 'HvacRecoveryPanel', props: ['recoveryOperationKey'], emits: ['recovered'], template: '<button data-testid="recovery-panel" @click="$emit(\'recovered\')">恢复旧隔离</button>' },
}))

import HvacControlPanel from './HvacControlPanel.vue'

const options = (canControl = true, currentTemperature: number | null = 24.5) => ({
  equipmentId: 'IDU-1', canControl, testOpenControls: false, reasonCode: canControl ? null : 'DISABLED', revision: canControl ? 'revision-1' : null, activeOperationId: null, currentTemperature,
  actions: [
    { action: 'ON_OFF', allowed: canControl, reasonCode: canControl ? null : 'DISABLED', values: ['on', 'off'], minimum: null, maximum: null },
    { action: 'TEMPERATURE', allowed: canControl, reasonCode: canControl ? null : 'TEMPERATURE_RANGE_UNKNOWN', values: [], minimum: 18, maximum: 30 },
    { action: 'MODE', allowed: canControl, reasonCode: null, values: ['cooling'], minimum: null, maximum: null },
  ],
})
const operation = (
  status: HvacControlOperationStatus,
  overrides: Partial<HvacControlOperation> = {},
): HvacControlOperation => ({
  operationId: 'OP-1', equipmentId: 'IDU-1', action: 'ON_OFF' as const, value: 'on', status,
  reasonCode: null, vendorCode: null, observedValue: null, createdAt: 1_700_000_000_000, updatedAt: 1_700_000_001_000, closedReason: status === 'CLOSED' ? '人工核验结束' : null,
  deadlineAt: 1_700_000_300_000, stableCount: 0, stableSince: null, lastReadAt: 1_700_000_001_000, unitStatus: 'operating',
  isolated: !['OBSERVED_MATCH', 'CLOUD_VERIFIED', 'CONFIRMED', 'REJECTED', 'NOT_SENT'].includes(status), cloudStable: status === 'OBSERVED_STABLE' || status === 'CONFIRMED' || status === 'CLOUD_VERIFIED', verificationPolicy: 'LEGACY_STABLE',
  ...overrides,
})

describe('大金内机离线控制面板', () => {
  afterEach(() => vi.useRealTimers())
  beforeEach(() => {
    vi.clearAllMocks()
    api.options.mockResolvedValue(options())
    api.operations.mockResolvedValue({ page: 1, size: 20, total: 0, items: [] })
    api.createOperation.mockResolvedValue(operation('ACCEPTED'))
    api.operation.mockResolvedValue(operation('ACCEPTED'))
    api.recheck.mockResolvedValue(operation('ACCEPTED'))
    api.close.mockResolvedValue(operation('CLOSED', { isolated: true }))
    api.confirm.mockResolvedValue(operation('CONFIRMED', { isolated: false }))
    api.release.mockResolvedValue(operation('CLOSED', { isolated: false, cloudStable: true }))
  })

  it('启停直接提交单次请求，不再要求选择动作或重复确认', async () => {
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('控制权限默认关闭')
    expect(wrapper.text()).not.toContain('我已核对设备和目标值')
    const turnOn = wrapper.find('[data-testid="turn-on-control"]')
    expect(turnOn.attributes('disabled')).toBeUndefined()
    await turnOn.trigger('click')
    await flushPromises()

    expect(api.createOperation).toHaveBeenCalledWith('IDU-1', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'component-control-key', revision: 'revision-1',
    })
    expect(wrapper.text()).toContain('厂家已受理')
    expect(wrapper.text()).toContain('已受理，等待回显')
    wrapper.unmount()
  })

  it('以服务端当前设温初始化输入，并在云端核验终态自动刷新权限快照', async () => {
    api.createOperation.mockResolvedValue(operation('CLOUD_VERIFIED', { isolated: false, cloudStable: true, reasonCode: 'CLOUD_VERIFIED_AUTO_FINISHED' }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    const input = wrapper.findComponent(ElInputNumber)
    expect(input.props('modelValue')).toBe(24.5)
    input.vm.$emit('update:modelValue', 26)
    await flushPromises()
    await wrapper.find('[data-testid="apply-temperature-control"]').trigger('click')
    await flushPromises()

    expect(api.createOperation).toHaveBeenCalledWith('IDU-1', {
      action: 'TEMPERATURE', value: '26', idempotencyKey: 'component-control-key', revision: 'revision-1',
    })
    expect(api.options).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('云端回显一致')
    expect(wrapper.text()).toContain('不等同于现场物理确认')
    expect(wrapper.text()).toContain('服务端已自动完成云端核验')
    expect(wrapper.emitted('completed')).toHaveLength(1)
    api.options.mockResolvedValue({ ...options(), activeOperationId: 'OP-1' })
    api.operation.mockResolvedValue(operation('CLOUD_VERIFIED', { isolated: false }))
    await wrapper.setProps({ refreshTick: 1 })
    await flushPromises()
    expect(wrapper.emitted('completed')).toHaveLength(1)
    wrapper.unmount()
  })

  it('快速轮询完成才通知监测刷新，开关先匹配但运行仍未停止时继续等待', async () => {
    vi.useFakeTimers()
    api.options.mockResolvedValue({ ...options(), testOpenControls: true })
    api.createOperation.mockResolvedValue(operation('ACCEPTED', {
      value: 'off', observedValue: 'off', verificationPolicy: 'TEST_FAST_V1', unitStatus: 'operating',
    }))
    api.operation.mockResolvedValue(operation('CLOUD_VERIFIED', {
      value: 'off', observedValue: 'off', verificationPolicy: 'TEST_FAST_V1', unitStatus: 'stopped',
      isolated: false, reasonCode: 'FAST_TARGET_READBACK_MATCH',
    }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    await wrapper.find('[data-testid="turn-off-control"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('completed')).toBeUndefined()
    expect(wrapper.find('[data-testid="control-operation-pending"]').text()).toContain('等待运行状态同步')
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(wrapper.emitted('completed')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('当前不可操作（原因代码：FAST_TARGET_READBACK_MATCH）')
    expect(wrapper.find('[data-testid="control-operation-pending"]').exists()).toBe(false)
    expect(api.createOperation).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it.each(['UNKNOWN', 'UNCONFIRMED', 'REJECTED', 'NOT_SENT', 'CLOSED'] as const)('目标回显匹配的 %s 不发出成功刷新通知', async status => {
    api.createOperation.mockResolvedValue(operation(status, { observedValue: 'on', isolated: false }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    await wrapper.find('[data-testid="turn-on-control"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('completed')).toBeUndefined()
    wrapper.unmount()
  })

  it('服务端 BUSY 快照显示等待回显，不误报厂家无控制能力', async () => {
    api.options.mockResolvedValue({ ...options(false), testOpenControls: true, reasonCode: 'BUSY', activeOperationId: 'OP-1' })
    api.operation.mockResolvedValue(operation('ACCEPTED', { observedValue: null, verificationPolicy: 'TEST_FAST_V1' }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    expect(wrapper.find('[data-testid="control-operation-pending"]').text()).toContain('正在读取最新状态')
    expect(wrapper.text()).not.toContain('当前设备暂不可控制')
    expect(wrapper.text()).not.toContain('厂家当前未返回可执行的控制能力')
    expect(api.createOperation).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('其他控制器操作占用与真正的设备能力限制分别提示', async () => {
    api.options.mockResolvedValue({ ...options(false), testOpenControls: true, reasonCode: 'CONTROLLER_BUSY' })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    expect(wrapper.text()).toContain('同一设备或控制器有尚未结束的操作')
    api.options.mockResolvedValue({ ...options(false), testOpenControls: true, reasonCode: 'CAPABILITY_UNKNOWN' })
    await wrapper.setProps({ refreshTick: 1 })
    await flushPromises()
    expect(wrapper.find('[data-testid="control-operation-pending"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('厂家尚未确认设备控制能力')
    wrapper.unmount()
  })

  it('切换设备后迟到的完成响应不刷新新设备', async () => {
    let finish!: (value: HvacControlOperation) => void
    api.createOperation.mockImplementationOnce(() => new Promise<HvacControlOperation>(resolve => { finish = resolve }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    await wrapper.find('[data-testid="turn-on-control"]').trigger('click')
    api.options.mockResolvedValue({ ...options(), equipmentId: 'IDU-2' })
    await wrapper.setProps({ equipmentId: 'IDU-2' })
    await flushPromises()
    finish(operation('CLOUD_VERIFIED', { isolated: false }))
    await flushPromises()
    expect(wrapper.emitted('completed')).toBeUndefined()
    wrapper.unmount()
  })

  it('测试快速回显立即提示请求待受理，且不把稳定采样套用到本次字段核验', async () => {
    let pendingResolve!: (operation: HvacControlOperation) => void
    api.createOperation.mockImplementationOnce(() => new Promise<HvacControlOperation>(resolve => { pendingResolve = resolve }))
    const testOptions = options()
    testOptions.testOpenControls = true
    api.options.mockResolvedValue(testOptions)
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    await wrapper.find('[data-testid="turn-on-control"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="control-submit-pending"]').text()).toContain('请求目标尚未写入实际状态')

    pendingResolve(operation('CLOUD_VERIFIED', {
      isolated: false, cloudStable: false, observedValue: 'on', verificationPolicy: 'TEST_FAST_V1', reasonCode: 'FAST_TARGET_READBACK_MATCH',
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('测试控制已开放')
    expect(wrapper.text()).toContain('云端回显一致')
    expect(wrapper.text()).toContain('仅核验本次目标字段')
    expect(wrapper.find('.operation-detail').text()).toContain('后端观测值')
    expect(wrapper.find('.operation-detail').text()).toContain('开')
    expect(wrapper.text()).not.toContain('云端稳定进度')
    expect(wrapper.text()).not.toContain('至少 3 次／60 秒')
    wrapper.unmount()
  })

  it('测试快速回显的未知结果展示最新回读，但不将目标匹配升级为成功', async () => {
    const testOptions = options()
    testOptions.testOpenControls = true
    api.options.mockResolvedValue(testOptions)
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operation('UNKNOWN', {
        verificationPolicy: 'TEST_FAST_V1', observedValue: 'on', cloudStable: false, stableCount: 0,
      })],
    })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('发送响应未确认，当前读值不代表原请求成功')
    expect(wrapper.find('.operation-detail').text()).toContain('后端观测值')
    expect(wrapper.find('.operation-detail').text()).toContain('开')
    expect(wrapper.text()).not.toContain('云端回显一致')
    wrapper.unmount()
  })

  it('仅启用服务端许可的启停值和稀疏设温白名单', async () => {
    const restricted = options()
    restricted.currentTemperature = 24
    restricted.actions[0] = { ...restricted.actions[0]!, values: ['off'] }
    restricted.actions[1] = { ...restricted.actions[1]!, values: ['20', '24'] }
    api.options.mockResolvedValue(restricted)
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.find('[data-testid="turn-on-control"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="turn-off-control"]').attributes('disabled')).toBeUndefined()
    const input = wrapper.findComponent(ElInputNumber)
    input.vm.$emit('update:modelValue', 21)
    await flushPromises()
    expect(wrapper.find('[data-testid="apply-temperature-control"]').attributes('disabled')).toBeDefined()

    input.vm.$emit('update:modelValue', 24)
    await flushPromises()
    expect(wrapper.find('[data-testid="apply-temperature-control"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('未知记录仍须人工关闭，关闭不会解除隔离且不需要确认勾选', async () => {
    api.options.mockResolvedValue(options(false))
    api.operations.mockResolvedValue({ page: 1, size: 20, total: 1, items: [operation('UNKNOWN', { observedValue: 'on', cloudStable: true, stableCount: 3 })] })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('请求结果未知；虽当前云端读取已稳定，仍不代表原请求成功')
    const close = wrapper.findAllComponents(ElButton).find(button => button.text() === '关闭记录')!
    await close.trigger('click')
    await wrapper.find('textarea').setValue('人工核验结束')
    await flushPromises()
    const submitClose = wrapper.findAllComponents(ElButton).find(button => button.text() === '确认关闭记录')!
    expect(submitClose.attributes('disabled')).toBeUndefined()
    await submitClose.trigger('click')
    await flushPromises()

    expect(api.close).toHaveBeenCalledWith('OP-1', '人工核验结束')
    expect(wrapper.text()).toContain('隔离仍保留')
    wrapper.unmount()
  })

  it('控制关闭时仍保留管理员历史异常恢复入口，恢复后刷新服务端控制快照', async () => {
    api.options.mockResolvedValue(options(false))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前设备不可控制')
    expect(wrapper.find('[data-testid="recovery-panel"]').exists()).toBe(true)
    await wrapper.find('[data-testid="recovery-panel"]').trigger('click')
    await flushPromises()
    expect(api.options).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('保留旧云端稳定待现场确认记录的人工确认路径', async () => {
    api.options.mockResolvedValue(options(false))
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operation('OBSERVED_STABLE', { stableCount: 3, stableSince: 1_700_000_100_000, lastReadAt: 1_700_000_200_000, cloudStable: true })],
    })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    expect(wrapper.text()).toContain('云端稳定待现场确认')
    const confirm = wrapper.findAllComponents(ElButton).find(button => button.text() === '现场确认')!
    await confirm.trigger('click')
    await wrapper.find('textarea').setValue('现场核对设备状态稳定')
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '确认现场核验')!.trigger('click')
    await flushPromises()

    expect(api.confirm).toHaveBeenCalledWith('OP-1', '现场核对设备状态稳定')
    expect(wrapper.text()).toContain('已现场确认')
    wrapper.unmount()
  })

  it('测试全开放处理旧异常也不展示现场确认和独立解除的旧步骤', async () => {
    api.options.mockResolvedValue({ ...options(false), testOpenControls: true })
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operation('UNKNOWN', { cloudStable: true, isolated: true, observedValue: 'on' })],
    })
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()
    const labels = wrapper.findAllComponents(ElButton).map(button => button.text())
    expect(labels).not.toContain('现场确认')
    expect(labels).not.toContain('独立解除隔离')
    expect(labels).not.toContain('关闭记录')
    expect(wrapper.find('[data-testid="recovery-panel"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('旧稳定采样核验')
    wrapper.unmount()
  })

  it('操作记录总数超过 20 条时支持翻页', async () => {
    api.operations.mockImplementation((_equipmentId: string, page = 1) => Promise.resolve({ page, size: 20, total: 21, items: [] }))
    const wrapper = mount(HvacControlPanel, { props: { equipmentId: 'IDU-1', refreshTick: 0 } })
    await flushPromises()

    const pagination = wrapper.findComponent(ElPagination)
    expect(pagination.exists()).toBe(true)
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.operations).toHaveBeenLastCalledWith('IDU-1', 2, 20)
    wrapper.unmount()
  })
})
