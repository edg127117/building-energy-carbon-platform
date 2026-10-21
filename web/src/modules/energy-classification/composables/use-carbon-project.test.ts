import { describe, expect, it, vi } from 'vitest'
import { nextTick, reactive, ref } from 'vue'
import { TransportError } from '@/infrastructure/http/public'
import { carbonProjectApi, energyCatalogApi } from '../api/carbon-project'
import { useCarbonProject } from './use-carbon-project'

vi.mock('../api/carbon-project', async importOriginal => {
  const actual = await importOriginal<typeof import('../api/carbon-project')>()
  return {
    ...actual,
    carbonProjectApi: { options: vi.fn().mockResolvedValue([]), listVersions: vi.fn(), getReadiness: vi.fn(), createVersion: vi.fn(), saveVersion: vi.fn(), transition: vi.fn() },
    energyCatalogApi: { listItems: vi.fn() },
  }
})

const version = (versionId: string, revision = 1) => ({
  versionId, buildingId: 'B-1', versionNo: 1, revision, status: 'DRAFT', effectiveFrom: null, effectiveTo: null,
  createdBy: 1, reviewedBy: null, configuration: { classificationVersionId: null, energies: [], exclusions: [] },
})

describe('项目能源与碳边界状态', () => {
  it('目录只保留已批准品种，保存成功后重新读取版本与修订号', async () => {
    vi.mocked(carbonProjectApi.listVersions).mockResolvedValue([version('P-1', 3)] as never).mockResolvedValueOnce([version('P-1', 3)] as never).mockResolvedValueOnce([version('P-1', 4)] as never)
    vi.mocked(carbonProjectApi.getReadiness).mockResolvedValue({ buildingId: 'B-1', versionId: 'P-1', ready: true, issues: [] })
    vi.mocked(energyCatalogApi.listItems).mockResolvedValue([
      { itemId: 'I-1', itemCode: 'ELEC', itemName: '电力', status: 'APPROVED', versionId: 'EV-1', versionNo: 1 },
      { itemId: 'I-2', itemCode: 'GAS', itemName: '燃气', status: 'DRAFT', versionId: 'EV-2', versionNo: 1 },
    ])
    vi.mocked(carbonProjectApi.saveVersion).mockResolvedValue(version('P-1', 4) as never)
    const building = ref('B-1')
    const workspace = useCarbonProject(() => building.value)
    await vi.waitFor(() => expect(workspace.selectedVersion.value?.revision).toBe(3))
    expect(workspace.catalog.value.map(item => item.itemCode)).toEqual(['ELEC'])

    await workspace.save({ classificationVersionId: null, energies: [], exclusions: [] }, '更新边界')

    expect(carbonProjectApi.saveVersion).toHaveBeenCalledWith('B-1', 'P-1', expect.objectContaining({ expectedRevision: 3, reason: '更新边界' }))
    expect(workspace.selectedVersion.value?.revision).toBe(4)
  })

  it('切换建筑后忽略先前建筑返回的迟到响应', async () => {
    let resolveOld!: (items: ReturnType<typeof version>[]) => void
    vi.mocked(carbonProjectApi.listVersions).mockImplementation(buildingId => buildingId === 'B-1'
      ? new Promise(resolve => { resolveOld = resolve }) as never
      : Promise.resolve([version('P-2')] as never))
    vi.mocked(energyCatalogApi.listItems).mockResolvedValue([])
    const building = ref('B-1')
    const workspace = useCarbonProject(() => building.value)
    building.value = 'B-2'
    await nextTick()
    await vi.waitFor(() => expect(workspace.selectedVersionId.value).toBe('P-2'))
    resolveOld([version('P-1')])
    await nextTick()
    expect(workspace.versions.value.map(item => item.versionId)).toEqual(['P-2'])
  })

  it('保留版本冲突错误供页面显示', async () => {
    vi.mocked(carbonProjectApi.listVersions).mockResolvedValue([version('P-1')])
    vi.mocked(carbonProjectApi.getReadiness).mockResolvedValue({ buildingId: 'B-1', versionId: 'P-1', ready: false, issues: [] })
    vi.mocked(energyCatalogApi.listItems).mockResolvedValue([])
    vi.mocked(carbonProjectApi.saveVersion).mockRejectedValue(new TransportError('request', 409, 409, 'VERSION_CONFLICT'))
    const building = ref('B-1')
    const workspace = useCarbonProject(() => building.value)
    await vi.waitFor(() => expect(workspace.selectedVersion.value?.versionId).toBe('P-1'))
    await workspace.save({ classificationVersionId: null, energies: [], exclusions: [] }, '修订')
    expect(workspace.error.value).toContain('状态已变化')
  })

  it('新建后选择服务端返回的新版本，保存接受Vue响应式草稿', async () => {
    vi.mocked(carbonProjectApi.listVersions).mockResolvedValueOnce([version('OLD')]).mockResolvedValue([version('NEW'), version('OLD')])
    vi.mocked(carbonProjectApi.getReadiness).mockResolvedValue({ buildingId: 'B-1', versionId: 'NEW', ready: false, issues: [] })
    vi.mocked(energyCatalogApi.listItems).mockResolvedValue([])
    vi.mocked(carbonProjectApi.createVersion).mockResolvedValue(version('NEW'))
    vi.mocked(carbonProjectApi.saveVersion).mockResolvedValue(version('NEW', 2))
    const workspace = useCarbonProject(() => 'B-1')
    await vi.waitFor(() => expect(workspace.selectedVersionId.value).toBe('OLD'))
    await workspace.create('新增')
    expect(workspace.selectedVersionId.value).toBe('NEW')
    const draft = reactive({ classificationVersionId: null, energies: [], exclusions: [] })
    expect(await workspace.save(draft, '保存')).toBe(true)
    expect(carbonProjectApi.saveVersion).toHaveBeenLastCalledWith('B-1', 'NEW', expect.objectContaining({ configuration: draft }))
  })
})
