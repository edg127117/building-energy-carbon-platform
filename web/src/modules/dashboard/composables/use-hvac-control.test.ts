import { effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import type { HvacControlOperation, HvacControlOperationStatus } from '../models/hvac-control'

const api = vi.hoisted(() => ({
  options: vi.fn(), createOperation: vi.fn(), operation: vi.fn(), operations: vi.fn(), recheck: vi.fn(), close: vi.fn(), confirm: vi.fn(), release: vi.fn(),
}))
const keyFactory = vi.hoisted(() => vi.fn())
vi.mock('../api/hvac-control', () => ({
  hvacControlApi: api,
  newHvacControlIdempotencyKey: keyFactory,
}))

import { useHvacControl } from './use-hvac-control'

const optionsFor = (equipmentId: string, activeOperationId: string | null = null) => ({
  equipmentId, canControl: true, testOpenControls: false, reasonCode: null, revision: 'r-1', activeOperationId,
  actions: [{ action: 'ON_OFF', allowed: true, reasonCode: null, values: ['on', 'off'], minimum: null, maximum: null }],
})
const historyFor = (equipmentId: string) => ({ page: 1, size: 20, total: 0, items: [] as never[], equipmentId })
const operationFor = (
  equipmentId: string,
  status: HvacControlOperationStatus = 'PREPARING',
  overrides: Partial<HvacControlOperation> = {},
): HvacControlOperation => ({
  operationId: `op-${equipmentId}`, equipmentId, action: 'ON_OFF' as const, value: 'on', status,
  reasonCode: null, vendorCode: null, observedValue: null, createdAt: 1, updatedAt: 2, closedReason: null,
  deadlineAt: null, stableCount: 0, stableSince: null, lastReadAt: null, unitStatus: null,
  isolated: !['OBSERVED_MATCH', 'CONFIRMED', 'REJECTED', 'NOT_SENT'].includes(status), cloudStable: status === 'OBSERVED_STABLE' || status === 'CONFIRMED', verificationPolicy: 'LEGACY_STABLE',
  ...overrides,
})
const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('离线控制状态资源', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    keyFactory.mockReturnValue('test-control-key')
    api.options.mockImplementation((id: string) => Promise.resolve(optionsFor(id)))
    api.operations.mockImplementation((id: string) => Promise.resolve(historyFor(id)))
    api.createOperation.mockImplementation((id: string) => Promise.resolve(operationFor(id)))
    api.operation.mockImplementation((id: string) => Promise.resolve(operationFor(id.replace('op-', ''))))
    api.recheck.mockResolvedValue(operationFor('A', 'OBSERVED_MATCH'))
    api.close.mockResolvedValue(operationFor('A', 'CLOSED', { isolated: true }))
    api.confirm.mockResolvedValue(operationFor('A', 'CONFIRMED', { isolated: false }))
    api.release.mockResolvedValue(operationFor('A', 'CLOSED', { isolated: false, cloudStable: true }))
  })
  afterEach(() => vi.useRealTimers())

  it('切换设备后拒绝旧设备的迟到权限响应', async () => {
    const delayedOptions = deferred<ReturnType<typeof optionsFor>>()
    api.options.mockImplementation((id: string) => id === 'A' ? delayedOptions.promise : Promise.resolve(optionsFor(id)))
    const equipmentId = ref('A')
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(equipmentId))!

    equipmentId.value = 'B'
    await nextTick()
    await Promise.resolve()
    expect(control.options.value?.equipmentId).toBe('B')

    delayedOptions.resolve(optionsFor('A'))
    await Promise.resolve()
    await Promise.resolve()
    expect(control.options.value?.equipmentId).toBe('B')
    scope.stop()
  })

  it('提交只创建操作记录，不将目标值伪造成实时当前值', async () => {
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    expect(api.createOperation).toHaveBeenCalledWith('A', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'test-control-key', revision: 'r-1',
    })
    expect(control.activeOperation.value?.value).toBe('on')
    expect(control.history.value.items[0]?.status).toBe('PREPARING')
    scope.stop()
  })

  it('提交前拒绝当前许可之外的枚举值、非整数和稀疏设温值', async () => {
    api.options.mockResolvedValue({
      ...optionsFor('A'),
      actions: [
        { action: 'ON_OFF', allowed: true, reasonCode: null, values: ['off'], minimum: null, maximum: null },
        { action: 'TEMPERATURE', allowed: true, reasonCode: null, values: ['20', '24'], minimum: 18, maximum: 30 },
      ],
    })
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    await control.submit('TEMPERATURE', '21')
    await control.submit('TEMPERATURE', '24.5')
    await control.submit('TEMPERATURE', '31')
    expect(api.createOperation).not.toHaveBeenCalled()
    expect(control.error.value).toContain('许可范围')

    await control.submit('TEMPERATURE', '24')
    expect(api.createOperation).toHaveBeenCalledWith('A', {
      action: 'TEMPERATURE', value: '24', idempotencyKey: 'test-control-key', revision: 'r-1',
    })
    scope.stop()
  })

  it('响应丢失重试复用幂等键，收到 Operation 后才生成新键', async () => {
    keyFactory.mockReturnValueOnce('retry-key').mockReturnValueOnce('next-key')
    api.createOperation
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(operationFor('A', 'REJECTED'))
      .mockResolvedValueOnce(operationFor('A', 'REJECTED'))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    await control.submit('ON_OFF', 'on')
    expect(api.createOperation).toHaveBeenNthCalledWith(1, 'A', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'retry-key', revision: 'r-1',
    })
    expect(api.createOperation).toHaveBeenNthCalledWith(2, 'A', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'retry-key', revision: 'r-1',
    })
    expect(keyFactory).toHaveBeenCalledTimes(1)

    await control.submit('ON_OFF', 'on')
    expect(api.createOperation).toHaveBeenLastCalledWith('A', {
      action: 'ON_OFF', value: 'on', idempotencyKey: 'next-key', revision: 'r-1',
    })
    expect(keyFactory).toHaveBeenCalledTimes(2)
    scope.stop()
  })

  it.each(['PREPARING', 'SENDING', 'ACCEPTED', 'UNKNOWN', 'UNCONFIRMED', 'OBSERVED_STABLE', 'CLOSED'] as const)('本地 %s 操作未结束或仍隔离时拒绝再次提交', async status => {
    api.createOperation.mockResolvedValueOnce(operationFor('A', status))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    expect(control.hasInFlightOperation.value).toBe(true)
    await control.submit('ON_OFF', 'off')
    expect(api.createOperation).toHaveBeenCalledTimes(1)
    expect(control.error.value).toContain('未结束')
    scope.stop()
  })

  it('组件作用域结束后停止有限状态轮询', async () => {
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()
    await control.submit('ON_OFF', 'on')
    scope.stop()

    await vi.advanceTimersByTimeAsync(3_000)
    expect(api.operation).not.toHaveBeenCalled()
  })

  it('云端核验终态自动结束轮询并刷新控制选项，但不把未知记录自动结束', async () => {
    api.createOperation.mockResolvedValue(operationFor('A', 'CLOUD_VERIFIED', { isolated: false, cloudStable: true }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()

    await control.submit('ON_OFF', 'on')
    await Promise.resolve()
    await Promise.resolve()
    expect(control.activeOperation.value?.status).toBe('CLOUD_VERIFIED')
    expect(control.hasInFlightOperation.value).toBe(false)
    expect(api.options).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(3_000)
    expect(api.operation).not.toHaveBeenCalled()
    scope.stop()
  })

  it('云端核验后的权限快照未返回时拒绝复用旧 revision 提交', async () => {
    const refreshedOptions = deferred<ReturnType<typeof optionsFor>>()
    api.options
      .mockResolvedValueOnce(optionsFor('A'))
      .mockReturnValueOnce(refreshedOptions.promise)
    api.createOperation.mockResolvedValue(operationFor('A', 'CLOUD_VERIFIED', { isolated: false, cloudStable: true }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    expect(control.optionsRefreshing.value).toBe(true)
    await control.submit('ON_OFF', 'off')
    expect(api.createOperation).toHaveBeenCalledTimes(1)
    expect(control.error.value).toContain('正在同步云端核验后的控制权限')

    refreshedOptions.resolve(optionsFor('A'))
    await Promise.resolve()
    await Promise.resolve()
    expect(control.optionsRefreshing.value).toBe(false)
    scope.stop()
  })

  it('云端核验后的权限快照失败会丢弃旧选项，重新加载后才允许再次提交', async () => {
    api.options
      .mockResolvedValueOnce(optionsFor('A'))
      .mockRejectedValueOnce(new Error('snapshot unavailable'))
      .mockResolvedValueOnce(optionsFor('A'))
    api.createOperation
      .mockResolvedValueOnce(operationFor('A', 'CLOUD_VERIFIED', { isolated: false, cloudStable: true }))
      .mockResolvedValueOnce(operationFor('A', 'REJECTED'))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.submit('ON_OFF', 'on')
    await Promise.resolve()
    await Promise.resolve()
    expect(control.options.value).toBeNull()
    await control.submit('ON_OFF', 'off')
    expect(api.createOperation).toHaveBeenCalledTimes(1)

    await control.refresh()
    await control.submit('ON_OFF', 'off')
    expect(api.createOperation).toHaveBeenCalledTimes(2)
    scope.stop()
  })

  it('状态轮询覆盖 300 秒观察窗，并在 110 次安全上限后停止', async () => {
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()
    await control.submit('ON_OFF', 'on')

    await vi.advanceTimersByTimeAsync(330_000)
    expect(api.operation).toHaveBeenCalledTimes(110)
    await vi.advanceTimersByTimeAsync(6_000)
    expect(api.operation).toHaveBeenCalledTimes(110)
    scope.stop()
  })

  it('TEST_FAST_V1 每秒读取状态，并按服务端期限确定轮询预算', async () => {
    api.createOperation.mockResolvedValue(operationFor('A', 'ACCEPTED', {
      verificationPolicy: 'TEST_FAST_V1', createdAt: 1_000, deadlineAt: 61_000,
    }))
    api.operation.mockResolvedValue(operationFor('A', 'ACCEPTED', {
      verificationPolicy: 'TEST_FAST_V1', createdAt: 1_000, deadlineAt: 61_000,
    }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()

    await control.submit('ON_OFF', 'on')
    await vi.advanceTimersByTimeAsync(3_000)

    expect(api.operation).toHaveBeenCalledTimes(3)
    scope.stop()
  })

  it('TEST_FAST_V1 的结果未知仍按服务端期限只读最新回显，且不重发写请求', async () => {
    api.createOperation.mockResolvedValue(operationFor('A', 'UNKNOWN', {
      verificationPolicy: 'TEST_FAST_V1', createdAt: 1_000, deadlineAt: 61_000,
    }))
    api.operation.mockResolvedValue(operationFor('A', 'UNKNOWN', {
      verificationPolicy: 'TEST_FAST_V1', createdAt: 1_000, deadlineAt: 61_000, observedValue: 'on',
    }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()

    await control.submit('ON_OFF', 'on')
    await vi.advanceTimersByTimeAsync(5_000)

    expect(control.activeOperation.value?.status).toBe('UNKNOWN')
    expect(control.activeOperation.value?.observedValue).toBe('on')
    expect(api.operation).toHaveBeenCalledTimes(5)
    expect(api.createOperation).toHaveBeenCalledTimes(1)
    scope.stop()
  })

  it('历史查询 403 不清空已读取的控制选项，也不作为整个控制面板失败', async () => {
    api.operations.mockRejectedValue(new TransportError('forbidden', 403))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    expect(control.options.value?.equipmentId).toBe('A')
    expect(control.historyForbidden.value).toBe(true)
    expect(control.error.value).toBeNull()
    scope.stop()
  })

  it('历史记录可以加载后续页', async () => {
    api.operations.mockImplementation((id: string, page = 1) => Promise.resolve({
      page, size: 20, total: 21, items: page === 2 ? [operationFor(id, 'UNKNOWN')] : [],
    }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.loadHistory(2)
    expect(api.operations).toHaveBeenLastCalledWith('A', 2, 20)
    expect(control.history.value.page).toBe(2)
    expect(control.history.value.items[0]?.operationId).toBe('op-A')
    scope.stop()
  })

  it('人工结束只接受未知或待核验记录，且原因长度为 5 至 500 个字符', async () => {
    api.operations.mockResolvedValue({ page: 1, size: 20, total: 1, items: [operationFor('A', 'UNKNOWN')] })
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.close('op-A', '短')
    expect(api.close).not.toHaveBeenCalled()
    expect(control.error.value).toContain('5～500')

    await control.close('op-A', '现场核对后结束')
    expect(api.close).toHaveBeenCalledWith('op-A', '现场核对后结束')
    scope.stop()
  })

  it('云端稳定待现场确认时停止自动轮询但继续占用，并只能通过现场说明确认', async () => {
    api.createOperation.mockResolvedValue(operationFor('A', 'OBSERVED_STABLE', {
      stableCount: 3, stableSince: 1, lastReadAt: 2, cloudStable: true,
    }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()

    await control.submit('ON_OFF', 'on')
    await vi.advanceTimersByTimeAsync(3_000)
    expect(api.operation).not.toHaveBeenCalled()
    expect(control.hasInFlightOperation.value).toBe(true)

    await control.confirm('op-A', '现场核对设备状态稳定')
    expect(api.confirm).toHaveBeenCalledWith('op-A', '现场核对设备状态稳定')
    scope.stop()
  })

  it('结果未知且尚未云端稳定时，即使本机已过期限也继续有限轮询', async () => {
    api.createOperation.mockResolvedValue(operationFor('A', 'UNKNOWN', { deadlineAt: 1, cloudStable: false }))
    api.operation.mockResolvedValue(operationFor('A', 'UNKNOWN', { deadlineAt: 1, cloudStable: false }))
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()
    vi.useFakeTimers()

    await control.submit('ON_OFF', 'on')
    await vi.advanceTimersByTimeAsync(3_000)
    expect(api.operation).toHaveBeenCalledWith('op-A')
    expect(control.activeOperation.value?.status).toBe('UNKNOWN')
    expect(control.hasInFlightOperation.value).toBe(true)
    scope.stop()
  })

  it('未知记录即使云端稳定也保持未知，并允许以现场说明独立解除隔离', async () => {
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operationFor('A', 'UNKNOWN', { observedValue: 'on', cloudStable: true, stableCount: 3, isolated: true })],
    })
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    expect(control.activeOperation.value?.status).toBe('UNKNOWN')
    expect(control.hasInFlightOperation.value).toBe(true)
    vi.useFakeTimers()
    await vi.advanceTimersByTimeAsync(3_000)
    expect(api.operation).not.toHaveBeenCalled()
    await control.release('op-A', '现场核对当前状态稳定')
    expect(api.release).toHaveBeenCalledWith('op-A', '现场核对当前状态稳定')
    scope.stop()
  })

  it('仅隔离中的已关闭记录可以重新核验', async () => {
    api.operations.mockResolvedValue({
      page: 1, size: 20, total: 1,
      items: [operationFor('A', 'CLOSED', { isolated: true, cloudStable: true })],
    })
    const scope = effectScope()
    const control = scope.run(() => useHvacControl(ref('A')))!
    await Promise.resolve()
    await Promise.resolve()

    await control.recheck('op-A')
    expect(api.recheck).toHaveBeenCalledWith('op-A')
    scope.stop()
  })
})
