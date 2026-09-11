import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { listEquipmentAssociations, runRelationAction, updateEquipmentAssociation } from './associations'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
beforeEach(() => vi.resetAllMocks())
describe('association HTTP contracts', () => {
  it('encodes identifiers and preserves null detach operations and revision', () => {
    listEquipmentAssociations('B/1', { versionId: 'D', page: 2, size: 20, unassigned: true })
    expect(requestApi).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/v1/relation-models/B%2F1/equipment-associations', params: { versionId: 'D', page: 2, size: 20, unassigned: true } }))
    updateEquipmentAssociation({ versionId: 'D/1', revision: 8 } as never, 'E', null, 'G')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'put', url: '/v1/relation-models/versions/D%2F1/structure/assignments', data: { objectType: 'EQUIPMENT', objectId: 'E', spaceId: null, systemGroupId: 'G', equipmentId: null, expectedRevision: 8 } })
  })
  it('uses model revision for activation and supplied idempotency key', () => {
    runRelationAction('activate', { buildingId: 'B', model: { modelRevision: 5 } as never, version: { versionId: 'D' } as never }, 'reason', 'retry-key')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'post', url: '/v1/relation-models/versions/D/activate', data: { reason: 'reason', expectedModelRevision: 5 }, headers: { 'Idempotency-Key': 'retry-key' } })
  })
})
