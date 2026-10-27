import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import { relationGovernanceApi } from '../api/relation-governance'
import type { RelationModelReference, RelationPage, RelationMeteringBoundary, RelationQueryMetadata, RelationVersionReference } from '../models/classification'
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
const model = (buildingId: string, activeVersionId = 'R1'): RelationModelReference => ({ buildingId, activeVersionId, draftVersionId: null, modelRevision: 1 })
const version = (buildingId: string, versionId = 'R1'): RelationVersionReference => ({ buildingId, versionId, versionNo: 1, revision: 1, status: 'EFFECTIVE', effectiveAt: metadata.effectiveAt })

describe('分类引用的正式关系快照', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(relationGovernanceApi.getModel).mockResolvedValue(model('B1'))
    vi.mocked(relationGovernanceApi.listVersions).mockResolvedValue([version('B1')])
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

  it('仅将已核实的空关系模型显示为未初始化状态', async () => {
    vi.mocked(relationGovernanceApi.getModel).mockRejectedValue(new TransportError('request', 404, undefined, 'RELATION_NOT_FOUND'))
    vi.mocked(relationGovernanceApi.listVersions).mockResolvedValue([])
    const workspace = useRelationGovernanceOptions()

    await workspace.loadBuilding('B1')

    expect(workspace.modelUninitialized.value).toBe(true)
    expect(workspace.error.value).toBeNull()
    expect(workspace.model.value).toBeNull()
    expect(workspace.versions.value).toEqual([])
  })

  it('在版本读取完成前不提前把模型未找到判断为空状态', async () => {
    let resolveVersions!: (value: RelationVersionReference[]) => void
    vi.mocked(relationGovernanceApi.getModel).mockRejectedValue(new TransportError('request', 404, undefined, 'RELATION_NOT_FOUND'))
    vi.mocked(relationGovernanceApi.listVersions).mockReturnValueOnce(new Promise(resolve => { resolveVersions = resolve }))
    const workspace = useRelationGovernanceOptions()

    const pending = workspace.loadBuilding('B1')
    expect(workspace.loading.value).toBe(true)
    expect(workspace.modelUninitialized.value).toBe(false)
    expect(workspace.error.value).toBeNull()

    resolveVersions([])
    await pending
    expect(workspace.modelUninitialized.value).toBe(true)
  })

  it('保留版本列表的 404，而不把它伪装成模型未初始化', async () => {
    vi.mocked(relationGovernanceApi.getModel).mockRejectedValue(new TransportError('request', 404, undefined, 'RELATION_NOT_FOUND'))
    vi.mocked(relationGovernanceApi.listVersions).mockRejectedValue(new TransportError('request', 404, undefined, 'RELATION_NOT_FOUND'))
    const workspace = useRelationGovernanceOptions()

    await workspace.loadBuilding('B1')

    expect(workspace.modelUninitialized.value).toBe(false)
    expect(workspace.error.value).toMatchObject({ code: 'RELATION_NOT_FOUND' })
  })

  it.each([
    ['无权限', new TransportError('forbidden', 403, undefined, 'RELATION_FORBIDDEN')],
    ['网络失败', new TransportError('network')],
    ['服务端失败', new TransportError('request', 500)],
    ['其他 404', new TransportError('request', 404, undefined, 'RELATION_VERSION_NOT_FOUND')],
  ])('不把%s伪装成模型未初始化', async (_, failure) => {
    vi.mocked(relationGovernanceApi.getModel).mockRejectedValue(failure)
    vi.mocked(relationGovernanceApi.listVersions).mockResolvedValue([])
    const workspace = useRelationGovernanceOptions()

    await workspace.loadBuilding('B1')

    expect(workspace.modelUninitialized.value).toBe(false)
    expect(workspace.error.value).not.toBeNull()
  })

  it('切换建筑后，旧建筑的未初始化响应不得覆盖新选择', async () => {
    let rejectOld!: (reason?: unknown) => void
    vi.mocked(relationGovernanceApi.getModel).mockImplementation(buildingId => {
      if (buildingId === 'B1') return new Promise((_, reject) => { rejectOld = reject })
      return Promise.resolve(model('B2', 'R2'))
    })
    vi.mocked(relationGovernanceApi.listVersions).mockImplementation(buildingId => Promise.resolve(buildingId === 'B2' ? [version('B2', 'R2')] : []))
    const workspace = useRelationGovernanceOptions()

    const oldBuilding = workspace.loadBuilding('B1')
    await workspace.loadBuilding('B2')
    rejectOld(new TransportError('request', 404, undefined, 'RELATION_NOT_FOUND'))
    await oldBuilding

    expect(workspace.buildingId.value).toBe('B2')
    expect(workspace.model.value?.buildingId).toBe('B2')
    expect(workspace.modelUninitialized.value).toBe(false)
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
