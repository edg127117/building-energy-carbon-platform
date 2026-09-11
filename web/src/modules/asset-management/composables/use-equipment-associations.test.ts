import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import * as api from '../api/associations'
import { useEquipmentAssociations } from './use-equipment-associations'

vi.mock('../api/associations', () => ({
  TransportError,
  listAssociationBuildings: vi.fn(), getRelationModel: vi.fn(), listRelationVersions: vi.fn(), listRelationReviews: vi.fn(),
  listEquipmentAssociations: vi.fn(), updateEquipmentAssociation: vi.fn(), validateRelationVersion: vi.fn(), getRelationDiff: vi.fn(), runRelationAction: vi.fn(),
}))
const draft = { versionId: 'draft', versionNo: 2, status: 'DRAFT', revision: 7, baseVersionId: 'effective', changeReason: 'move', submittedBy: null }
const equipment = { equipmentId: 'E', equipmentCode: 'E1', equipmentName: 'Equipment', spaceId: 'S1', spaceName: 'Room 1', systemGroupId: 'G', systemGroupName: 'System' }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.getRelationModel).mockResolvedValue({ buildingId: 'B', governanceMode: 'GOVERNED', activeVersionId: 'effective', draftVersionId: 'draft', modelRevision: 2 })
  vi.mocked(api.listRelationVersions).mockResolvedValue([draft])
  vi.mocked(api.listRelationReviews).mockResolvedValue([])
  vi.mocked(api.listEquipmentAssociations).mockResolvedValue({ buildingId: 'B', versionId: null, versionRevision: 7, page: 1, size: 20, total: 1, items: [equipment], spaces: [], systems: [] })
})
describe('equipment association flow', () => {
  it('uses the displayed association revision rather than a newer version-list revision', async () => {
    vi.mocked(api.listRelationVersions).mockResolvedValue([{ ...draft, revision: 8 }])
    const state = useEquipmentAssociations()
    await state.selectBuilding('B'); await state.selectVersion('draft')
    await state.save(equipment, 'S2', 'G')
    expect(api.updateEquipmentAssociation).toHaveBeenCalledWith(expect.objectContaining({ revision: 7 }), 'E', 'S2', 'G')
  })
  it('reads effective projection first and edits only an explicit draft using its revision', async () => {
    const state = useEquipmentAssociations()
    await state.selectBuilding('B')
    expect(await state.save(equipment, 'S2', 'G')).toBe(false)
    expect(api.updateEquipmentAssociation).not.toHaveBeenCalled()
    await state.selectVersion('draft')
    expect(await state.save(equipment, null, 'G')).toBe(true)
    expect(api.updateEquipmentAssociation).toHaveBeenCalledWith(draft, 'E', null, 'G')
    expect(api.listEquipmentAssociations).toHaveBeenLastCalledWith('B', expect.objectContaining({ versionId: 'draft' }))
    expect(state.notice.value).toContain('草稿')
  })
  it('distinguishes missing model from forbidden and network failures', async () => {
    vi.mocked(api.getRelationModel).mockRejectedValue(new TransportError('request', 404))
    const state = useEquipmentAssociations()
    await state.selectBuilding('B')
    expect(state.model.value).toBeNull()
    expect(state.data.value?.total).toBe(1)
    vi.mocked(api.getRelationModel).mockRejectedValue(new TransportError('forbidden', 403))
    await state.query()
    expect(state.error.value).not.toBe('')
    expect(state.data.value).toBeNull()
  })
  it('does not retry stale revisions or claim success after a write conflict', async () => {
    const state = useEquipmentAssociations()
    await state.selectBuilding('B'); await state.selectVersion('draft')
    vi.mocked(api.updateEquipmentAssociation).mockRejectedValue(new TransportError('request', 409))
    expect(await state.save(equipment, 'S2', 'G')).toBe(false)
    expect(api.updateEquipmentAssociation).toHaveBeenCalledTimes(1)
    expect(state.notice.value).toBe('')
    expect(state.error.value).toContain('版本')
  })
  it('blocks scope switching and duplicate writes while a save is pending', async () => {
    let complete!: (value: typeof draft) => void
    vi.mocked(api.updateEquipmentAssociation).mockReturnValue(new Promise(resolve => { complete = resolve }))
    const state = useEquipmentAssociations()
    await state.selectBuilding('B'); await state.selectVersion('draft')
    const pending = state.save(equipment, 'S2', 'G')
    await state.selectBuilding('OTHER')
    expect(state.buildingId.value).toBe('B')
    expect(await state.save(equipment, 'S3', 'G')).toBe(false)
    complete(draft); await pending
    expect(api.updateEquipmentAssociation).toHaveBeenCalledTimes(1)
  })
  it('refreshes the effective projection after activation without optimistic relabeling', async () => {
    const state = useEquipmentAssociations()
    await state.selectBuilding('B'); await state.selectVersion('draft')
    vi.mocked(api.runRelationAction).mockResolvedValue({ ...draft, status: 'EFFECTIVE' })
    await state.act('activate', 'approved change', 'key')
    expect(state.versionId.value).toBe('')
    expect(api.listEquipmentAssociations).toHaveBeenLastCalledWith('B', expect.objectContaining({ versionId: undefined }))
  })
})
