import { effectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getEquipmentReadings, getEquipmentTrendHistory } from '../api/assets'
import { useLedgerRuntime } from './use-ledger-runtime'
import type { AssetEquipment, AssetEquipmentReadings } from '../models/assets'
import { ledgerDisplayPointValue } from '../models/ledger'

vi.mock('../api/assets', () => ({ getEquipmentReadings: vi.fn(), getEquipmentTrendHistory: vi.fn() }))
const owners: ReturnType<typeof effectScope>[] = []
const setup = () => { const owner = effectScope(); owners.push(owner); return owner.run(useLedgerRuntime)! }
const equipment = (id: string) => ({ equipmentId: id, buildingId: 'B1' }) as AssetEquipment
const response = (id: string): AssetEquipmentReadings => ({ equipmentId: id, buildingId: 'B1', generatedAt: 0, points: [] })
beforeEach(() => vi.resetAllMocks())
afterEach(() => owners.splice(0).forEach(owner => owner.stop()))

describe('台账右栏读数范围与门禁', () => {
  it('页面卸载后不再查询右栏读数或历史', async () => {
    const state = setup()
    owners.at(-1)!.stop()
    await state.loadReadings(equipment('E1'))
    await state.loadTrend('E1')
    expect(getEquipmentReadings).not.toHaveBeenCalled()
    expect(getEquipmentTrendHistory).not.toHaveBeenCalled()
  })
  it('只请求选中设备的右栏读数，清理后旧响应不得恢复原范围', async () => {
    let finishOld!: (value: AssetEquipmentReadings) => void
    vi.mocked(getEquipmentReadings).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce(response('NEW'))
    const state = setup()
    const old = state.loadReadings(equipment('OLD'))
    await flushPromises()
    expect(getEquipmentReadings).toHaveBeenCalledTimes(1)
    state.clear()
    const next = state.loadReadings(equipment('NEW'))
    finishOld(response('OLD'))
    await Promise.all([old, next])

    expect(getEquipmentReadings).toHaveBeenCalledTimes(2)
    expect(state.readings.value?.equipmentId).toBe('NEW')
  })
  it('拒绝身份错配的读数和历史，不把失败伪装为空数据', async () => {
    vi.mocked(getEquipmentReadings).mockResolvedValue({ ...response('E1'), buildingId: 'OTHER' })
    vi.mocked(getEquipmentTrendHistory).mockResolvedValue({ equipmentId: 'OTHER', startTime: '', endTime: '', series: [] })
    const state = setup()
    await state.loadReadings(equipment('E1'))
    expect(state.readings.value).toBeNull()
    expect(state.readingsError.value).toBeTruthy()
    await state.loadTrend('E1')
    expect(state.trend.value).toBeNull()
    expect(state.trendError.value).toBeTruthy()
  })
  it('保留后端默认展示测点的真实零值，拒绝非可展示状态和非有限数', () => {
    const point = { value: 0, status: 'AVAILABLE', usageStatus: 'AVAILABLE' } as Parameters<typeof ledgerDisplayPointValue>[0]
    expect(ledgerDisplayPointValue(point)).toBe(0)
    expect(ledgerDisplayPointValue({ ...point, value: 999, status: 'QUALITY_BLOCKED' })).toBeNull()
    expect(ledgerDisplayPointValue({ ...point, status: 'NO_DATA' })).toBeNull()
    expect(ledgerDisplayPointValue({ ...point, value: NaN })).toBeNull()
  })
})
