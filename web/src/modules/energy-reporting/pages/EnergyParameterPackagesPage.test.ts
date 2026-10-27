import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = vi.hoisted(() => ({ query: { buildingId: 'B-1' } as Record<string, string>, replace: vi.fn(), push: vi.fn() }))
const sessionState = vi.hoisted(() => ({ user: { id: 8, roles: ['BUILDING_OWNER'] as string[] } }))
const reportingBuildings = vi.hoisted(() => ({
  records: { value: [] as Array<{ buildingId: string, buildingName: string }> }, total: { value: 0 }, page: { value: 1 },
  loading: { value: false }, error: { value: '' }, search: vi.fn(),
}))
const parameterWorkspace = vi.hoisted(() => ({
  catalog: { value: {
    packages: [], existingVersions: [], denominatorContext: [],
    catalogRequirements: [{ catalogType: 'CONVERSION', requiredCode: 'B01', versions: [] }],
  } },
  loading: { value: false }, saving: { value: false }, error: { value: '' },
  load: vi.fn(), preview: vi.fn(), importPackage: vi.fn(),
}))
const carbonFactors = vi.hoisted(() => ({
  listDenominators: vi.fn(), createDenominator: vi.fn(), reviewDenominator: vi.fn(), activateDenominator: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/operations/energy/energyItems', query: routeState.query }),
  useRouter: () => ({ replace: routeState.replace, push: routeState.push }),
}))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => sessionState }))
vi.mock('../composables/use-reporting-buildings', () => ({ useReportingBuildings: () => reportingBuildings }))
vi.mock('../composables/use-parameter-packages', () => ({ useParameterPackages: () => parameterWorkspace }))
vi.mock('@/modules/energy-classification/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/energy-classification/public')>()), carbonFactorsApi: carbonFactors }))

import EnergyParameterPackagesPage from './EnergyParameterPackagesPage.vue'

const denominatorRows = [
  { denominatorVersionId: 'DEN-1', configRevision: 1, denominatorType: 'BUILDING_AREA', value: '100', unitCode: 'M2', effectiveFrom: '2026-01-01', status: 'PENDING_REVIEW' },
  { denominatorVersionId: 'DEN-2', configRevision: 2, denominatorType: 'RESIDENT_POPULATION', value: '20', unitCode: 'PERSON', effectiveFrom: '2026-01-01', status: 'APPROVED' },
]

async function mountPage() {
  const wrapper = mount(EnergyParameterPackagesPage, { attachTo: document.body, global: { directives: { loading: {} } } })
  await flushPromises()
  return wrapper
}

describe('专业参数包权限边界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionState.user = { id: 8, roles: ['BUILDING_OWNER'] }
    parameterWorkspace.catalog.value = {
      packages: [], existingVersions: [], denominatorContext: [],
      catalogRequirements: [{ catalogType: 'CONVERSION', requiredCode: 'B01', versions: [] }],
    }
    parameterWorkspace.load.mockResolvedValue(undefined)
    carbonFactors.listDenominators.mockResolvedValue(denominatorRows)
  })

  it('BUILDING_OWNER 只读取目录和分母版本，不显示专业参数或分母写入动作', async () => {
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('目录依赖与待办')
    expect(wrapper.text()).toContain('面积与人数分母')
    expect(wrapper.find('[data-test="parameter-read-only"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="parameter-write-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="parameter-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="parameter-import"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="denominator-write-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="denominator-create"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="denominator-review"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="denominator-reject"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="denominator-activate"]').exists()).toBe(false)
    expect(carbonFactors.createDenominator).not.toHaveBeenCalled()
    expect(carbonFactors.reviewDenominator).not.toHaveBeenCalled()
    expect(carbonFactors.activateDenominator).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('ENERGY_MANAGER 保留专业参数和分母写入入口', async () => {
    sessionState.user = { id: 9, roles: ['ENERGY_MANAGER'] }
    const wrapper = await mountPage()

    expect(wrapper.find('[data-test="parameter-read-only"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="parameter-write-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('2021、2022年全国0.5703默认因子的数据年可留空')
    const yearInputs = wrapper.findAllComponents({ name: 'ElInputNumber' }).filter(input => input.props('min') === 1900)
    expect(yearInputs.length).toBeGreaterThanOrEqual(2)
    expect(yearInputs.every(input => input.props('modelValue') === null)).toBe(true)
    expect(wrapper.find('[data-test="parameter-preview"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="parameter-import"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="denominator-write-form"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="denominator-create"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="denominator-review"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="denominator-reject"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="denominator-activate"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
