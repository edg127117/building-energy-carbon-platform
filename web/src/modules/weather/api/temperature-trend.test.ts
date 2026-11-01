import { beforeEach, expect, it, vi } from 'vitest'
import { getTemperatureTrend } from './temperature-trend'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/infrastructure/http/public', () => ({ requestApi: api }))
beforeEach(() => api.mockReset())
it('只请求明确建筑的只读期间，并传递取消信号，不提交采集任务', async () => {
  api.mockResolvedValue({ period: '2024' })
  const controller = new AbortController()
  expect(await getTemperatureTrend('OTHER/BUILDING', 'YEAR', '2024', controller.signal)).toEqual({ period: '2024' })
  expect(api).toHaveBeenCalledWith({ url: '/v1/weather/buildings/OTHER%2FBUILDING/temperature-trend', method: 'GET', params: { periodType: 'YEAR', period: '2024' }, signal: controller.signal })
})
