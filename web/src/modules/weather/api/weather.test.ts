import { beforeEach, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { weatherApi } from './weather'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn(), TransportError: class extends Error {} }))
beforeEach(() => vi.mocked(requestApi).mockReset())
it('pages accessible buildings independently of configured weather locations', async () => {
  vi.mocked(requestApi).mockResolvedValueOnce({ records: [{ buildingId: 'a', buildingName: 'A' }], total: 2 }).mockResolvedValueOnce({ records: [{ buildingId: 'b', buildingName: 'B' }], total: 2 })
  expect(await weatherApi.buildings()).toHaveLength(2)
  expect(requestApi).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/building/list', params: { page: 2, size: 100 } }))
})
it('sends encoded building paths, half-open hourly bounds and an abort signal', async () => {
  const signal = new AbortController().signal
  vi.mocked(requestApi).mockResolvedValue([])
  await weatherApi.hourly('a/b', 'HISTORY_HOURLY', '2026-09-01T16:00:00Z', '2026-09-02T16:00:00Z', signal)
  expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({ url: '/v1/weather/buildings/a%2Fb/hourly', signal, params: { product: 'HISTORY_HOURLY', from: '2026-09-01T16:00:00Z', to: '2026-09-02T16:00:00Z' } }))
})
it('uses stable page items for jobs and preserves command idempotency header', async () => {
  vi.mocked(requestApi).mockResolvedValueOnce({ items: [{ id: 'job' }], page: 1, size: 10, total: 1 })
  expect((await weatherApi.jobs('b', 1)).items[0].id).toBe('job')
  const payload = { systemId: 's', pointId: 'p', start: '2026-09-01', end: '2026-09-02' }
  await weatherApi.calculate('b', payload, 'same-key')
  expect(requestApi).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/v1/weather/buildings/b/energy-comparison/jobs', data: payload, headers: { 'Idempotency-Key': 'same-key' } }))
})
