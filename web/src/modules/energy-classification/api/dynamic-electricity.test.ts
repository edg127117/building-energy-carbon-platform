import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readDynamicElectricity } from './dynamic-electricity'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('动态电力监测读取接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })
  afterEach(() => vi.useRealTimers())

  it('仅准备状态轮询，完成后返回；完整性错误不会被吞掉', async () => {
    vi.useFakeTimers()
    vi.mocked(requestApi).mockRejectedValueOnce(Object.assign(new Error('preparing'), { status: 503, errorCode: 'PERIOD_DISPLAY_PREPARING' }))
    const ready = readDynamicElectricity('B', 'YEAR', '2026')
    await vi.advanceTimersByTimeAsync(2_000)
    await expect(ready).resolves.toEqual({})
    expect(requestApi).toHaveBeenCalledTimes(2)
    const failure = Object.assign(new Error('integrity'), { errorCode: 'PERIOD_DISPLAY_INTEGRITY' })
    vi.mocked(requestApi).mockRejectedValueOnce(failure)
    await expect(readDynamicElectricity('B', 'YEAR', '2026')).rejects.toBe(failure)
    expect(requestApi).toHaveBeenCalledTimes(3)
    const unrelatedStatus = Object.assign(new Error('unexpected status'), { status: 502, errorCode: 'PERIOD_DISPLAY_PREPARING' })
    vi.mocked(requestApi).mockRejectedValueOnce(unrelatedStatus)
    await expect(readDynamicElectricity('B', 'YEAR', '2026')).rejects.toBe(unrelatedStatus)
    expect(requestApi).toHaveBeenCalledTimes(4)
  })

  it('切换期间取消等待，不再请求旧年份', async () => {
    vi.useFakeTimers()
    vi.mocked(requestApi).mockRejectedValueOnce(Object.assign(new Error('preparing'), { status: 503, errorCode: 'PERIOD_DISPLAY_PREPARING' }))
    const controller = new AbortController()
    const pending = readDynamicElectricity('B', 'YEAR', '2025', controller.signal)
    const assertion = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await assertion
    await vi.advanceTimersByTimeAsync(3_000)
    expect(requestApi).toHaveBeenCalledTimes(1)
  })

  it('准备轮询在 120 秒截止并限制单次请求时长', async () => {
    vi.useFakeTimers()
    vi.mocked(requestApi).mockRejectedValue(Object.assign(new Error('preparing'), { status: 503, errorCode: 'PERIOD_DISPLAY_PREPARING' }))
    const pending = readDynamicElectricity('B', 'YEAR', '2026')
    const assertion = expect(pending).rejects.toThrow('DYNAMIC_DISPLAY_PREPARATION_TIMEOUT')

    await vi.advanceTimersByTimeAsync(120_000)
    await assertion

    expect(requestApi).toHaveBeenCalled()
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({ timeout: 120_000 }))
    expect(requestApi).toHaveBeenCalledTimes(60)
  })

  it('只对编码后的建筑路径读取已保存的年/月动态结果并传递取消信号', async () => {
    const controller = new AbortController()

    await readDynamicElectricity('B/01', 'MONTH', '2026-09', controller.signal)

    expect(requestApi).toHaveBeenCalledTimes(1)
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({
      method: 'get',
      url: '/v1/carbon-management/buildings/B%2F01/dynamic-electricity',
      params: { periodType: 'MONTH', period: '2026-09' },
      signal: controller.signal,
    }))
  })
})
