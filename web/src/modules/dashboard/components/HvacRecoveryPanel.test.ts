import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { ElInputNumber, ElSelect } from '@/shared/ui'
import type { HvacControlOperation, HvacControlRecoveryObservation } from '../models/hvac-control'

const api = vi.hoisted(() => ({
  deviceGrantAccess: vi.fn(), recovery: vi.fn(), observeRecovery: vi.fn(), completeRecovery: vi.fn(), continueRecovery: vi.fn(),
}))
vi.mock('../api/hvac-control', () => ({ hvacControlApi: api }))

import HvacRecoveryPanel from './HvacRecoveryPanel.vue'

const operation = (overrides: Partial<HvacControlOperation> = {}): HvacControlOperation => ({
  operationId: 'OLD-1', equipmentId: 'IDU-1', action: 'ON_OFF', value: 'on', status: 'CLOSED', reasonCode: 'MANUALLY_CLOSED_UNKNOWN', vendorCode: null,
  observedValue: null, createdAt: 1_700_000_000_000, updatedAt: 1_700_000_001_000, closedReason: '历史异常关闭', deadlineAt: null,
  stableCount: 0, stableSince: null, lastReadAt: null, unitStatus: null, isolated: true, cloudStable: false, verificationPolicy: 'LEGACY_STABLE',
  ...overrides,
})
const readyObservation = (overrides: Partial<HvacControlRecoveryObservation> = {}): HvacControlRecoveryObservation => ({
  operationId: 'OLD-1', onOff: 'on', temperature: 24, mode: null, fanSpeed: null, airFlow: null, unitStatus: 'operating',
  stableCount: 3, stableSince: 1_700_000_000_000, lastReadAt: 1_700_000_060_000, canRecover: true, proof: 'fresh-proof', reasonCode: null,
  ...overrides,
})
function mountPanel(equipmentId = 'IDU-1') {
  return mount(HvacRecoveryPanel, { props: { equipmentId } })
}
async function setBaseline(wrapper: ReturnType<typeof mount>) {
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'on')
  wrapper.findComponent(ElInputNumber).vm.$emit('update:modelValue', 24)
  await flushPromises()
}

describe('历史异常恢复面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    api.deviceGrantAccess.mockResolvedValue({ canManage: true, testOpenControls: false })
    api.recovery.mockResolvedValue({ operation: operation(), eligible: true, simpleRecovery: false, reasonCode: null })
    api.observeRecovery.mockResolvedValue(readyObservation())
    api.completeRecovery.mockResolvedValue(operation({ isolated: false, reasonCode: 'RECOVERY_CURRENT_BASELINE_RELEASED' }))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('非管理员不请求恢复端点', async () => {
    api.deviceGrantAccess.mockResolvedValue({ canManage: false, testOpenControls: false })
    const wrapper = mountPanel()
    await flushPromises()

    expect(api.deviceGrantAccess).toHaveBeenCalledTimes(1)
    expect(api.recovery).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="observe-recovery"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('测试开放时按当前状态继续关闭历史异常，不填写基线或等待三次采样', async () => {
    api.deviceGrantAccess.mockResolvedValue({ canManage: false, testOpenControls: true })
    api.recovery.mockResolvedValue({ operation: operation(), eligible: true, simpleRecovery: true, reasonCode: null })
    api.continueRecovery.mockResolvedValue(operation({ isolated: false, reasonCode: 'TEST_CURRENT_STATE_RELEASED' }))
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('按当前状态继续')
    expect(wrapper.find('[data-testid="observe-recovery"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="complete-recovery"]').exists()).toBe(false)
    await wrapper.find('[data-testid="continue-recovery"]').trigger('click')
    await flushPromises()

    expect(api.continueRecovery).toHaveBeenCalledWith('OLD-1', { reason: '测试模式按当前状态继续处理历史异常隔离，不确认旧请求成功' })
    expect(api.observeRecovery).not.toHaveBeenCalled()
    expect(api.completeRecovery).not.toHaveBeenCalled()
    expect(wrapper.emitted('recovered')).toHaveLength(1)
    wrapper.unmount()
  })

  it('本页操作进入未知后只刷新一次恢复候选，并显示按当前状态继续', async () => {
    api.deviceGrantAccess.mockResolvedValue({ canManage: false, testOpenControls: true })
    api.recovery
      .mockResolvedValueOnce({ operation: null, eligible: false, simpleRecovery: false, reasonCode: null })
      .mockResolvedValueOnce({ operation: operation(), eligible: true, simpleRecovery: true, reasonCode: null })
    const wrapper = mount(HvacRecoveryPanel, { props: { equipmentId: 'IDU-1', recoveryOperationKey: null } })
    await flushPromises()

    expect(wrapper.find('.recovery-panel').exists()).toBe(false)
    await wrapper.setProps({ recoveryOperationKey: 'OLD-1:UNKNOWN:isolated:occupied' })
    await flushPromises()
    expect(api.recovery).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="continue-recovery"]').exists()).toBe(true)

    await wrapper.setProps({ recoveryOperationKey: 'OLD-1:UNKNOWN:isolated:occupied' })
    await flushPromises()
    expect(api.recovery).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('不猜测现场基线；管理员确认实际值后才发起只读核验', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('原指令目标')
    expect(wrapper.text()).toContain('只关闭历史异常隔离，不代表旧命令执行成功')
    expect(wrapper.find('[data-testid="observe-recovery"]').attributes('disabled')).toBeDefined()
    await setBaseline(wrapper)
    await wrapper.find('[data-testid="observe-recovery"]').trigger('click')
    await flushPromises()

    expect(api.observeRecovery).toHaveBeenCalledWith('OLD-1', { onOff: 'on', temperature: 24 })
    expect(wrapper.find('[data-testid="complete-recovery"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('观察期间不并发请求，按间隔继续观察，并在五分钟窗口结束后停止', async () => {
    let resolveFirst: ((value: HvacControlRecoveryObservation) => void) | undefined
    api.observeRecovery.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
    const wrapper = mountPanel()
    await flushPromises()
    await setBaseline(wrapper)
    await wrapper.find('[data-testid="observe-recovery"]').trigger('click')
    await flushPromises()

    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.observeRecovery).toHaveBeenCalledTimes(1)
    resolveFirst!(readyObservation())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.observeRecovery).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(270_000)
    expect(api.observeRecovery.mock.calls.length).toBeLessThanOrEqual(10)
    wrapper.unmount()
  })

  it('按服务端新鲜证明完成恢复并发出刷新事件', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await setBaseline(wrapper)
    await wrapper.find('[data-testid="observe-recovery"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="complete-recovery"]').trigger('click')
    await flushPromises()

    expect(api.completeRecovery).toHaveBeenCalledWith('OLD-1', {
      onOff: 'on', temperature: 24, proof: 'fresh-proof', reason: '现场确认当前设置，按新基线恢复旧隔离，不重发旧指令', onsiteConfirmed: true,
    })
    expect(wrapper.emitted('recovered')).toHaveLength(1)
    expect(wrapper.text()).toContain('已按当前确认基线解除历史隔离，原指令仍未确认成功')
    wrapper.unmount()
  })

  it('切换设备后忽略旧观察响应', async () => {
    let resolveOld: ((value: HvacControlRecoveryObservation) => void) | undefined
    api.observeRecovery.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    api.recovery.mockImplementation((equipmentId: string) => Promise.resolve({
      operation: equipmentId === 'IDU-1' ? operation() : null, eligible: equipmentId === 'IDU-1', simpleRecovery: false, reasonCode: null,
    }))
    const wrapper = mountPanel()
    await flushPromises()
    await setBaseline(wrapper)
    await wrapper.find('[data-testid="observe-recovery"]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ equipmentId: 'IDU-2' })
    await flushPromises()
    resolveOld!(readyObservation())
    await flushPromises()

    expect(api.recovery).toHaveBeenLastCalledWith('IDU-2')
    expect(wrapper.find('.recovery-panel').exists()).toBe(false)
    expect(wrapper.find('[data-testid="complete-recovery"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
