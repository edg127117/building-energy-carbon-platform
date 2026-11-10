import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useScreenEnergyFlow } from './use-screen-energy-flow'
const { queryEnergyFlow } = vi.hoisted(() => ({ queryEnergyFlow: vi.fn() }))
vi.mock('@/modules/energy-classification/public', () => ({ queryEnergyFlow }))
const result = (buildingId = 'A', month = '2026-09') => ({ buildingId, month, unit: 'TCE', state: 'SELECTION_REQUIRED', flows: [], conversionOptions: [{ energyItemCode: 'ELECTRICITY', unit: 'kWh', parameterVersionId: 'P' }] })
describe('energy flow read lifecycle', () => {
  beforeEach(() => { vi.clearAllMocks() })
  it('submits explicit choices without changing building configuration or computing values', async () => {
    queryEnergyFlow.mockResolvedValue(result())
    let state!: ReturnType<typeof useScreenEnergyFlow>
    const wrapper = mount(defineComponent({ setup() { state = useScreenEnergyFlow(ref('A'), ref('2026-09')); return () => h('div') } }))
    await flushPromises()
    expect(state.choices.value).toHaveLength(1)
    state.select('ELECTRICITY', 'kWh', 'P')
    await flushPromises()
    expect(queryEnergyFlow).toHaveBeenLastCalledWith('A', '2026-09', [{ energyItemCode: 'ELECTRICITY', unit: 'kWh', parameterVersionId: 'P' }], expect.any(AbortSignal))
    wrapper.unmount()
  })
  it('clears choices on scope change and rejects late or mismatched responses', async () => {
    let resolve!: (value: unknown) => void
    queryEnergyFlow.mockImplementationOnce(() => new Promise(next => { resolve = next })).mockResolvedValue(result('B'))
    const building = ref('A')
    let state!: ReturnType<typeof useScreenEnergyFlow>
    const wrapper = mount(defineComponent({ setup() { state = useScreenEnergyFlow(building, ref('2026-09')); return () => h('div') } }))
    building.value = 'B'
    await flushPromises()
    resolve(result('A'))
    await flushPromises()
    expect(state.report.value?.buildingId).toBe('B')
    expect(state.selections.value).toEqual([])
    queryEnergyFlow.mockResolvedValue(result('OTHER'))
    await state.reload()
    expect(state.report.value).toBeNull()
    expect(state.failed.value).toBe(true)
    wrapper.unmount()
  })
})
