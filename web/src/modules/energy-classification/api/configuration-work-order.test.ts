import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { configurationWorkOrderApi } from './configuration-work-order'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
describe('工作单契约', () => {
  it('只发送明确步骤与服务端快照指纹，不批量自动发布', async () => {
    const command = { fingerprint: 'snapshot', domain: 'PROJECT' as const, action: 'APPROVE' as const, reason: '审核依据', effectiveFrom: null, idempotencyKey: 'key' }
    await configurationWorkOrderApi.get('B/1', 'V/2')
    await configurationWorkOrderApi.execute('B/1', 'V/2', command)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'post', url: '/v1/carbon-projects/buildings/B%2F1/versions/V%2F2/work-order/commands', data: command })
  })
})
