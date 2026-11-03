import { afterEach, describe, expect, it, vi } from 'vitest'
import { readPreparedQuery, type PreparedQueryEnvelope } from './prepared-query'

const envelope = (overrides: Partial<PreparedQueryEnvelope<string>> = {}): PreparedQueryEnvelope<string> => ({
  contractVersion: 'CARBON_DISPLAY_QUERY_V1', kind: 'JOINT_FLOW', buildingId: 'B', queryKey: 'q', sourceVersion: 'v',
  state: 'READY', data: 'result', retryAfterSeconds: 1, ...overrides,
})

afterEach(() => vi.useRealTimers())

describe('准备式展示查询轮询', () => {
  it('按服务端建议间隔轮询并仅返回 READY 数据', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn().mockResolvedValueOnce(envelope({ state: 'PREPARING', data: null })).mockResolvedValueOnce(envelope())
    const pending = readPreparedQuery(fetch, { kind: 'JOINT_FLOW', buildingId: 'B' })
    await vi.advanceTimersByTimeAsync(1_000)
    await expect(pending).resolves.toBe('result')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('拒绝错建筑、错类型和 READY 缺少 data 的响应', async () => {
    await expect(readPreparedQuery(async () => envelope({ buildingId: 'C' }), { kind: 'JOINT_FLOW', buildingId: 'B' }))
      .rejects.toThrow('CARBON_DISPLAY_QUERY_ENVELOPE_MISMATCH')
    await expect(readPreparedQuery(async () => envelope({ contractVersion: 'OTHER' as never }), { kind: 'JOINT_FLOW', buildingId: 'B' }))
      .rejects.toThrow('CARBON_DISPLAY_QUERY_ENVELOPE_MISMATCH')
    await expect(readPreparedQuery(async () => envelope({ kind: 'CARBON_INTENSITY' }), { kind: 'JOINT_FLOW', buildingId: 'B' }))
      .rejects.toThrow('CARBON_DISPLAY_QUERY_ENVELOPE_MISMATCH')
    await expect(readPreparedQuery(async () => envelope({ data: null }), { kind: 'JOINT_FLOW', buildingId: 'B' }))
      .rejects.toThrow('CARBON_DISPLAY_QUERY_READY_WITHOUT_DATA')
  })

  it('等待期间可取消，并对未完成请求执行总时限', async () => {
    vi.useFakeTimers()
    const controller = new AbortController()
    const fetch = vi.fn().mockResolvedValue(envelope({ state: 'PREPARING', data: null }))
    const pending = readPreparedQuery(fetch, { kind: 'JOINT_FLOW', buildingId: 'B' }, controller.signal, { timeoutMs: 3_000 })
    const cancelled = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await cancelled

    let requestSignal: AbortSignal | undefined
    const slow = readPreparedQuery(signal => {
      requestSignal = signal
      return new Promise<PreparedQueryEnvelope<string>>(() => {})
    }, { kind: 'JOINT_FLOW', buildingId: 'B' }, undefined, { timeoutMs: 2_000 })
    const timedOut = expect(slow).rejects.toThrow('CARBON_DISPLAY_QUERY_TIMEOUT')
    await vi.advanceTimersByTimeAsync(2_000)
    await timedOut
    expect(requestSignal?.aborted).toBe(true)
  })
})
