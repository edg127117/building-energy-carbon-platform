import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { relationStagingApi } from './relation-staging'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
describe('新档案暂存契约', () => {
  it('所有读取和写入固定在选定建筑版本，取消和归属更新携带修订', async () => {
    await relationStagingApi.list('B/1', 'V/2')
    await relationStagingApi.options('B/1', 'V/2')
    await relationStagingApi.remove('B/1', 'V/2', 'A/3', 7, '取消依据')
    await relationStagingApi.assignment('V/2', { objectType: 'EQUIPMENT', objectId: 'A', spaceId: null, systemGroupId: null, equipmentId: null, expectedRevision: 8 })
    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/relation-models/B%2F1/versions/V%2F2/staged-assets' })
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'delete', url: '/v1/relation-models/B%2F1/versions/V%2F2/staged-assets/A%2F3', data: { expectedRevision: 7, reason: '取消依据' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'put', url: '/v1/relation-models/versions/V%2F2/structure/assignments', data: expect.objectContaining({ expectedRevision: 8, spaceId: null }) }))
  })
})
