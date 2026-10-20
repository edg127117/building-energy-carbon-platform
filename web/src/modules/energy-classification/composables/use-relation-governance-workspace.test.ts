import { beforeEach, describe, expect, it, vi } from 'vitest'
import { relationGovernanceApi } from '../api/relation-governance'
import { useRelationGovernanceWorkspace } from './use-relation-governance-workspace'

vi.mock('../api/relation-governance', () => ({
  relationGovernanceApi: {
    getModel: vi.fn(), listVersions: vi.fn(), listReviews: vi.fn(), getVersionDetail: vi.fn(),
    listVersionAssignments: vi.fn(), listVersionStructures: vi.fn(), listBoundaryCatalogue: vi.fn(),
    listNodes: vi.fn(), createBoundary: vi.fn(), validateVersion: vi.fn(),
  },
  collectRelationPages: (load: (page: number, size: number) => Promise<unknown>) => load(1, 100),
  RelationCatalogueLimitError: class extends Error {},
}))

function version(building: string) {
  return { buildingId: building, versionId: `R-${building}`, versionNo: 1, revision: 1, status: 'DRAFT', effectiveAt: null }
}
const metadata = { buildingId: 'B1', versionId: 'R-B1', versionNo: 1, effectiveAt: null, modelRevision: 1 }
const emptyPage = { metadata, page: 1, size: 100, total: 0, items: [] }

describe('关系治理写入后的异步隔离', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(relationGovernanceApi.getModel).mockImplementation(async building => ({ buildingId: building, activeVersionId: null, draftVersionId: `R-${building}`, modelRevision: 1 }))
    vi.mocked(relationGovernanceApi.listVersions).mockImplementation(async building => [version(building)])
    vi.mocked(relationGovernanceApi.listReviews).mockResolvedValue([])
    vi.mocked(relationGovernanceApi.getVersionDetail).mockImplementation(async building => ({ metadata, version: version(building), counts: {}, meterStructures: [] }))
    vi.mocked(relationGovernanceApi.listVersionAssignments).mockResolvedValue(emptyPage)
    vi.mocked(relationGovernanceApi.listVersionStructures).mockResolvedValue(emptyPage)
    vi.mocked(relationGovernanceApi.listBoundaryCatalogue).mockResolvedValue({ ...emptyPage, editableCatalogue: true })
    vi.mocked(relationGovernanceApi.listNodes).mockResolvedValue({ ...emptyPage, labelNature: 'CURRENT_ASSET_LABELS' })
  })

  it('旧建筑保存完成后不跳回旧版本或向新建筑发起旧版本刷新', async () => {
    let finish!: () => void
    vi.mocked(relationGovernanceApi.createBoundary).mockReturnValueOnce(new Promise(done => { finish = () => done({ boundaryId: 'BD1', boundaryCode: 'BD1', boundaryName: '待确认边界', energyType: null, confirmationStatus: 'PENDING_EXPERT', status: 'ACTIVE' }) }))
    const workspace = useRelationGovernanceWorkspace()
    await workspace.loadBuilding('B1')
    const saving = workspace.saveBoundary({ boundaryCode: 'BD1', boundaryName: '待确认边界', energyType: null, confirmationStatus: 'PENDING_EXPERT', evidenceReference: null }, null)
    await workspace.loadBuilding('B2')
    finish()
    await saving
    expect(workspace.selectedVersion.value?.versionId).toBe('R-B2')
    expect(relationGovernanceApi.getVersionDetail).not.toHaveBeenCalledWith('B2', 'R-B1')
  })

  it('旧版本校验结果不进入新建筑', async () => {
    let finish!: () => void
    vi.mocked(relationGovernanceApi.validateVersion).mockReturnValueOnce(new Promise(done => { finish = () => done({ buildingId: 'B1', versionId: 'R-B1', revision: 1, errorCount: 0, pendingExpertCount: 0, warningCount: 0, issues: [] }) }))
    const workspace = useRelationGovernanceWorkspace()
    await workspace.loadBuilding('B1')
    const validating = workspace.validateSelected()
    await workspace.loadBuilding('B2')
    finish()
    await validating
    expect(workspace.validation.value).toBeNull()
    expect(workspace.error.value).toBeNull()
  })
})
