import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readDailyTrends } from './daily-trends'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
beforeEach(() => { vi.mocked(requestApi).mockReset() })
afterEach(() => { vi.useRealTimers() })
it('encodes building identity and sends an intact month and cancellation signal', async () => {
  const signal = new AbortController().signal
  await readDailyTrends('other/building', '2024-02', signal)
  expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/energy-reporting/buildings/other%2Fbuilding/daily-trends/summary', params: { month: '2024-02' }, signal })
})
const preparing = { buildingId: 'B', month: '2026-01', state: 'PREPARING', contractVersion: 'DAILY_ELECTRICITY_STATIC_CARBON_SUMMARY_V1', retryAfterSeconds: 2 }
it('polls a preparing summary and returns its ready result without arithmetic', async () => {
  vi.useFakeTimers()
  const ready = { ...preparing, state: 'COMPLETE', days: [{ electricityKwh: '12.3400' }] }
  vi.mocked(requestApi).mockResolvedValueOnce(preparing).mockResolvedValueOnce(ready)
  const result = readDailyTrends('B', '2026-01')
  await vi.advanceTimersByTimeAsync(2000)
  expect(await result).toBe(ready)
  expect(requestApi).toHaveBeenCalledTimes(2)
})
it('cancels preparation polling when its card scope is aborted', async () => {
  vi.useFakeTimers()
  vi.mocked(requestApi).mockResolvedValue(preparing)
  const controller = new AbortController(), result = readDailyTrends('B', '2026-01', controller.signal)
  const assertion = expect(result).rejects.toMatchObject({ name: 'AbortError' })
  await vi.advanceTimersByTimeAsync(0); controller.abort(); await assertion
  await vi.advanceTimersByTimeAsync(10000)
  expect(requestApi).toHaveBeenCalledTimes(1)
})
it('rejects another building preparation and stops polling after its bounded deadline', async () => {
  vi.useFakeTimers()
  vi.mocked(requestApi).mockResolvedValue({ ...preparing, buildingId: 'OTHER' })
  await expect(readDailyTrends('B', '2026-01')).rejects.toThrow('DAILY_SUMMARY_SCOPE_MISMATCH')
  vi.mocked(requestApi).mockResolvedValue(preparing)
  const result = readDailyTrends('B', '2026-01'), assertion = expect(result).rejects.toThrow('DAILY_SUMMARY_PREPARATION_TIMEOUT')
  await vi.advanceTimersByTimeAsync(90000); await assertion
})
