import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listAssetLedger } from '../api/assets'
import { useLedgerScope } from './use-ledger-scope'
import type { AssetLedgerItem, AssetLedgerPage, AssetLedgerQuery } from '../models/ledger'

vi.mock('../api/assets', () => ({ listAssetLedger: vi.fn() }))

const owners: ReturnType<typeof effectScope>[] = []
const scope = () => {
  const owner = effectScope()
  const value = owner.run(useLedgerScope)!
  owners.push(owner)
  return value
}

const query = (buildingId = 'B1'): AssetLedgerQuery => ({ page: 1, size: 20, buildingId, professionalGroup: 'ALL' })
const item = (id: string, buildingId = 'B1') => ({
  equipment: { equipmentId: id, buildingId },
  classificationSource: 'TEST_RULE',
  ruleRevision: 1,
  classificationRevision: 0,
}) as AssetLedgerItem
const response = (nextQuery: AssetLedgerQuery, id = 'E1'): AssetLedgerPage => ({
  page: nextQuery.page,
  size: nextQuery.size,
  total: 31,
  items: [item(id, nextQuery.buildingId ?? 'B1')],
  groupCounts: { ALL: 31, POWER: 5, HVAC: 20, RENEWABLE: 1, WATER: 2, ENVIRONMENT: 1, UNCLASSIFIED: 2 },
  summary: { assets: 31, points: 98, online: 18, offline: 6, communicationError: 0, communicationUnknown: 5, notMonitored: 2, delayed: 3 },
  generatedAt: 1_735_689_600_000,
})

beforeEach(() => vi.resetAllMocks())
afterEach(() => owners.splice(0).forEach(owner => owner.stop()))

describe('台账分页聚合查询', () => {
  it('页面卸载后不再发起查询', async () => {
    const state = scope()
    owners.at(-1)!.stop()

    await state.load(query())

    expect(listAssetLedger).not.toHaveBeenCalled()
  })

  it('一次请求保留后端分页、专业计数和范围摘要，不再拉取全量设备', async () => {
    const request = { ...query(), page: 2, professionalGroup: 'HVAC' as const }
    vi.mocked(listAssetLedger).mockResolvedValue(response(request))
    const state = scope()

    await state.load(request)

    expect(listAssetLedger).toHaveBeenCalledTimes(1)
    expect(listAssetLedger).toHaveBeenCalledWith(request)
    expect(state.data.value).toMatchObject({
      page: 2,
      total: 31,
      groupCounts: { HVAC: 20 },
      summary: { online: 18, communicationError: 0, communicationUnknown: 5, notMonitored: 2, delayed: 3 },
    })
  })

  it('拒绝与请求不一致的分页响应，不显示可能属于其他页面的数据', async () => {
    const request = query()
    vi.mocked(listAssetLedger).mockResolvedValue({ ...response(request), page: 2 })
    const state = scope()

    await state.load(request)

    expect(state.data.value).toBeNull()
    expect(state.error.value).toBeTruthy()
  })

  it('拒绝跨建筑或重复设备，并在失败时撤下已有台账页', async () => {
    const request = query('B1')
    const crossBuilding = { ...response(request), items: [item('OTHER-BUILDING', 'B2')] }
    const duplicate = { ...response(request), items: [item('E1', 'B1'), item('E1', 'B1')] }
    vi.mocked(listAssetLedger).mockResolvedValueOnce(response(request)).mockResolvedValueOnce(crossBuilding).mockResolvedValueOnce(duplicate)
    const state = scope()

    await state.load(request)
    expect(state.data.value?.items[0]?.equipment.equipmentId).toBe('E1')
    await state.load(request)
    expect(state.data.value).toBeNull()
    expect(state.error.value).toContain('当前查询范围')
    await state.load(request)
    expect(state.data.value).toBeNull()
    expect(state.error.value).toContain('重复')
  })

  it('新查询完成后丢弃迟到旧响应', async () => {
    let finishOld!: (value: AssetLedgerPage) => void
    const oldQuery = query('OLD')
    const newQuery = query('NEW')
    vi.mocked(listAssetLedger)
      .mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(response(newQuery, 'NEW-EQUIPMENT'))
    const state = scope()

    const oldLoad = state.load(oldQuery)
    await state.load(newQuery)
    finishOld(response(oldQuery, 'OLD-EQUIPMENT'))
    await oldLoad

    expect(state.data.value?.items[0]?.equipment.equipmentId).toBe('NEW-EQUIPMENT')
  })
})
