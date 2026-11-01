import { expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readDailyTrends } from './daily-trends'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
it('encodes building identity and sends an intact month and cancellation signal', async () => {
  const signal = new AbortController().signal
  await readDailyTrends('other/building', '2024-02', signal)
  expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/energy-reporting/buildings/other%2Fbuilding/daily-trends', params: { month: '2024-02' }, signal })
})
