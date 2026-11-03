import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useJointFlow } from './use-joint-flow'
import type { ScreenPeriod } from '../models/screen-period'
import type { JointFlowView } from '@/modules/energy-classification/public'

const api = vi.hoisted(() => vi.fn())
vi.mock('@/modules/energy-classification/public', () => ({ queryPreparedJointFlow: api }))
vi.mock('@/locales', () => ({ t: (key: string) => key }))

function jointFlowFixture(): JointFlowView {
  const carbon = { state: 'READY', reason: null, unit: 'tCO2e', total: '0', unallocated: '0',
    nodes: [
      { id: 'E', code: 'ELEC', name: '电力', depth: 0, order: 1, value: '0' },
      { id: 'F', code: 'F8', name: '八层', depth: 1, order: 1, value: '0' },
      { id: 'T', code: 'TOTAL', name: '建筑合计', depth: 2, order: 0, value: '0' },
    ], links: [{ source: 'E', target: 'F', value: '0' }, { source: 'F', target: 'T', value: '0' }] }
  return { buildingId: 'B', periodType: 'MONTH', period: '2024-01', throughMonth: null, timezoneId: 'Asia/Shanghai',
    startInclusive: '2023-12-31T16:00:00Z', endExclusive: '2024-01-31T16:00:00Z', state: 'READY', classificationBasis: 'CONFIGURED_VIEW',
    configurationVersionId: 'CFG-1', configurationHash: 'hash', resultVersion: 'v', missingMonths: [], sources: [], parameterVersionIds: [], formulaVersionIds: [], standardCoalVersionIds: [], conversionOptions: [], warnings: [],
    dimensions: [
      { key: 'ENERGY_TYPE', title: '能源类型', source: 'ENERGY_ITEM', nodes: [{ code: 'ELEC', name: '电力', order: 1 }] },
      { key: 'FLOOR', title: '楼层', source: 'FROZEN_SCHEME', schemeCode: 'FLOOR', scopeLevel: 'FLOOR', nodes: [{ code: 'F8', name: '八层', order: 1 }] },
    ], coverage: { totalAtoms: 1, allocatedAtoms: 1, dimensions: [{ key: 'ENERGY_TYPE', allocatedAtoms: 1, unallocatedAtoms: 0 }, { key: 'FLOOR', allocatedAtoms: 1, unallocatedAtoms: 0 }] },
    carbon, energy: { ...carbon, state: 'SELECTION_REQUIRED', unit: 'tce', total: null, nodes: [], links: [] } }
}

const wrappers: Array<ReturnType<typeof mount>> = []
function harness() {
  const building = ref('B'), period = ref<ScreenPeriod>({ kind: 'month', value: '2024-01' })
  let state!: ReturnType<typeof useJointFlow>
  const wrapper = mount(defineComponent({ setup() { state = useJointFlow(building, period); return () => null } }))
  wrappers.push(wrapper)
  return { state, building, period, wrapper }
}

beforeEach(() => { api.mockReset(); api.mockResolvedValue(jointFlowFixture()) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('联合流请求生命周期', () => {
  it('默认只让后端解析建筑当前选用配置，并保留真实零值', async () => {
    const loaded = harness()
    await flushPromises()

    expect(api.mock.calls[0]![0]).toMatchObject({ buildingId: 'B', periodType: 'MONTH', period: '2024-01' })
    expect(api.mock.calls[0]![0]).not.toHaveProperty('configurationVersionId')
    expect(loaded.state.data.value.carbonFlow?.data.total).toBe(0)
    expect(loaded.state.data.value.energyFlow).toBeUndefined()
  })

  it('换建筑和卸载会取消旧请求，旧响应不能回填当前作用域', async () => {
    let resolveOld!: (value: ReturnType<typeof jointFlowFixture>) => void
    api.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const { state, building, wrapper } = harness()
    const firstSignal = api.mock.calls[0]![1] as AbortSignal
    api.mockResolvedValue({ ...jointFlowFixture(), buildingId: 'OTHER' })
    building.value = 'OTHER'
    await flushPromises()
    resolveOld(jointFlowFixture())
    await flushPromises()

    expect(firstSignal.aborted).toBe(true)
    expect(state.view.value?.buildingId).toBe('OTHER')
    const signal = api.mock.calls.at(-1)![1] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })

  it('拒绝草稿预览或错误作用域，并显式清空流图而非回退原型', async () => {
    api.mockResolvedValue({ ...jointFlowFixture(), classificationBasis: 'DRAFT_PREVIEW' })
    const { state } = harness()
    await flushPromises()

    expect(state.state.value).toBe('FAILED')
    expect(state.data.value).toEqual({ carbonFlow: undefined, energyFlow: undefined })
  })
})
