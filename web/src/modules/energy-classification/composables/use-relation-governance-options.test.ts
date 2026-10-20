import { beforeEach, describe, expect, it, vi } from 'vitest'
import { relationGovernanceApi } from '../api/relation-governance'
import type { RelationPage, RelationMeteringBoundary, RelationQueryMetadata } from '../models/classification'
import { useRelationGovernanceOptions } from './use-relation-governance-options'

vi.mock('../api/relation-governance', () => ({
  relationGovernanceApi: {
    getModel: vi.fn(), listVersions: vi.fn(), listEffectiveBoundaries: vi.fn(),
    listEffectiveStructures: vi.fn(), listNodes: vi.fn(), listVersionAssignments: vi.fn(), listVersionStructures: vi.fn(),
  },
  collectRelationPages: (load: (page: number, size: number) => Promise<unknown>) => load(1, 100),
}))

const metadata: RelationQueryMetadata = {
  buildingId: 'B1', versionId: 'R1', versionNo: 1, effectiveAt: '2026-01-01T00:00:00',
  modelRevision: 1,
}
const page = { metadata, page: 1, size: 100, total: 0, items: [] }

describe('分类引用的正式关系快照', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(relationGovernanceApi.getModel).mockResolvedValue({ buildingId: 'B1', activeVersionId: 'R1', draftVersionId: null, modelRevision: 1 })
    vi.mocked(relationGovernanceApi.listVersions).mockResolvedValue([{ buildingId: 'B1', versionId: 'R1', versionNo: 1, revision: 1, status: 'EFFECTIVE', effectiveAt: metadata.effectiveAt }])
    vi.mocked(relationGovernanceApi.listEffectiveBoundaries).mockResolvedValue(page)
    vi.mocked(relationGovernanceApi.listEffectiveStructures).mockResolvedValue(page)
    vi.mocked(relationGovernanceApi.listNodes).mockResolvedValue({ ...page, labelNature: 'CURRENT_ASSET_LABELS' })
  })

  it('加载后保留选中版本，使快照与绑定目录可见', async () => {
    const workspace = useRelationGovernanceOptions()
    await workspace.loadBuilding('B1')
    await workspace.loadSelectedVersion('B1', 'R1')
    expect(workspace.selectedVersion.value?.versionId).toBe('R1')
    expect(workspace.metadata.value?.versionId).toBe('R1')
    expect(workspace.detailLoading.value).toBe(false)
    expect(workspace.error.value).toBeNull()
  })

  it('切换建筑后旧目录响应不得覆盖新选择', async () => {
    let resolve!: (value: RelationPage<RelationMeteringBoundary>) => void
    vi.mocked(relationGovernanceApi.listEffectiveBoundaries).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const workspace = useRelationGovernanceOptions()
    await workspace.loadBuilding('B1')
    const pending = workspace.loadSelectedVersion('B1', 'R1')
    await workspace.loadBuilding('B2')
    resolve(page)
    await pending
    expect(workspace.buildingId.value).toBe('B2')
    expect(workspace.selectedVersion.value).toBeNull()
    expect(workspace.metadata.value).toBeNull()
  })
})
