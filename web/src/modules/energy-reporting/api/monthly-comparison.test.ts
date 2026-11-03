import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { queryMonthlyComparison } from './monthly-comparison'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('monthly comparison read contract', () => {
  it('uses a calendar month and backend time zone without a calculation request', async () => {
    const signal = new AbortController().signal
    await queryMonthlyComparison('B/1', '2026-09', signal)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'get', url: '/v1/energy-reporting/buildings/B%2F1/monthly-comparison', params: { month: '2026-09-01', timezoneId: 'Asia/Shanghai' }, signal })
  })
  it('rejects invalid month inputs before transport', () => {
    vi.mocked(requestApi).mockClear()
    for (const month of ['2026-13', '2026-9', '2026-09-01', 'bad']) expect(() => queryMonthlyComparison('B', month)).toThrow('INVALID_MONTHLY_QUERY')
    expect(requestApi).not.toHaveBeenCalled()
  })
})
