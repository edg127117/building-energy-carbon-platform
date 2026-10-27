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

  it('按后端实际目录类型和身份显示能源名称与吨等规范单位', async () => {
    parameterWorkspace.catalog.value.catalogRequirements = [
      { catalogType: 'ENERGY_ITEM', requiredCode: 'ELECTRICITY', versions: [] },
      { catalogType: 'ENERGY_ITEM', requiredCode: 'NATURAL_GAS', versions: [] },
      { catalogType: 'MEASUREMENT_UNIT', requiredCode: 'T', versions: [] },
      { catalogType: 'MEASUREMENT_UNIT', requiredCode: 'NM3', versions: [] },
      { catalogType: 'MEASUREMENT_UNIT', requiredCode: 'MWH', versions: [] },
    ]
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('电力')
    expect(wrapper.text()).toContain('天然气')
    expect(wrapper.text()).toContain('计量单位')
    expect(wrapper.text()).toContain('吨（t）')
    expect(wrapper.text()).toContain('标准立方米（Nm³）')
    expect(wrapper.text()).toContain('兆瓦时（MWh）')
    expect(wrapper.text()).not.toContain('未命名目录版本')
    wrapper.unmount()
  })

  it('电力当量条目使用官方系数与规范单位，公式未审核时不能导入', async () => {
    sessionState.user = { id: 9, roles: ['ENERGY_MANAGER'] }
    parameterWorkspace.catalog.value = {
      packages: [{ packageCode: 'B01', packageName: '折标准备参数', items: [{
        itemCode: 'ELECTRICITY_CALORIFIC_TCE_PARAMETER', itemName: '电力当量折标参数', targetType: 'CONVERSION_PARAMETER',
        sourceValue: '0.1229', sourceUnit: 'KGCE/KWH', normalizedValue: '0.0001229', normalizedUnit: 'TCE_PER_KWH', requiredInputs: [],
      }] }],
      existingVersions: [], denominatorContext: [], catalogRequirements: [],
    } as never
    parameterWorkspace.preview.mockResolvedValue({
      packageCode: 'B01', readyForImport: false,
      items: [{ itemCode: 'ELECTRICITY_CALORIFIC_TCE_PARAMETER', status: 'BLOCKED', normalizedValue: '0.0001229',
        normalizedUnit: 'TCE_PER_KWH', dependencies: [{ dependencyType: 'CONVERSION_FORMULA', versionId: null,
          status: 'MISSING_OR_CONFLICT', message: '未找到唯一适用公式' }] }],
      issues: [{ itemCode: 'ELECTRICITY_CALORIFIC_TCE_PARAMETER', severity: 'BLOCKER',
        code: 'ELECTRICITY_CALORIFIC_FORMULA_NOT_UNIQUE', message: '公式未审核或有冲突' }],
    } as never)
    const wrapper = await mountPage()
    const selector = wrapper.findAllComponents({ name: 'ElSelect' })
      .find(select => select.attributes('data-test') === 'parameter-package')
    if (!selector) throw new Error('Missing parameter package selector')
    selector.vm.$emit('update:modelValue', 'B01')
    await flushPromises()
    await wrapper.find('input[type="checkbox"]').setValue(true)
    expect(wrapper.text()).toContain('电力当量折标参数')
    expect(wrapper.text()).toContain('0.1229 千克标准煤/千瓦时（kgce/kWh）')
    expect(wrapper.text()).toContain('0.0001229 吨标准煤/千瓦时（tce/kWh）')
    await wrapper.find('[data-test="parameter-preview"]').trigger('click')
    await flushPromises()
    expect(parameterWorkspace.preview).toHaveBeenCalledWith(expect.objectContaining({
      itemCodes: ['ELECTRICITY_CALORIFIC_TCE_PARAMETER'],
    }))
    expect(wrapper.find('[data-test="preview-issue-action"]').text()).toContain('先建立并审核覆盖适用期的电力当量折标公式')
    expect(wrapper.find('[data-test="parameter-import"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="parameter-import"]').trigger('click')
    expect(parameterWorkspace.importPackage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('ENERGY_MANAGER 保留专业参数和分母写入入口', async () => {
    sessionState.user = { id: 9, roles: ['ENERGY_MANAGER'] }
    const wrapper = await mountPage()

    expect(wrapper.find('[data-test="parameter-read-only"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="parameter-write-form"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('数据年反映因子资料对应的年份')
    expect(wrapper.text()).toContain('平方米（m²）')
    expect(wrapper.findAll('details').some(detail => detail.find('summary').text() === '技术详情')).toBe(true)
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

  it('预检主区域显示中文处理动作，原始服务端说明只保留在折叠技术详情', async () => {
    sessionState.user = { id: 9, roles: ['ENERGY_MANAGER'] }
    parameterWorkspace.catalog.value = {
      packages: [{
        packageCode: 'B02', packageName: '碳因子候选', items: [{
          itemCode: 'ELECTRICITY_FACTOR', itemName: '全国电力位置法碳因子候选', targetType: 'CARBON_FACTOR',
          sourceValue: '0.5703', sourceUnit: 'TCO2/MWH', normalizedValue: '570.3', normalizedUnit: 'KG_CO2/MWH', requiredInputs: [],
        }],
      }],
      existingVersions: [], denominatorContext: [],
      catalogRequirements: [{ catalogType: 'CARBON_FACTOR', requiredCode: 'ELECTRICITY_FACTOR', versions: [] }],
    } as never
    parameterWorkspace.preview.mockResolvedValue({
      packageCode: 'B02', readyForImport: false,
      items: [{
        itemCode: 'ELECTRICITY_FACTOR', status: 'BLOCKER', normalizedValue: '570.3', normalizedUnit: 'KG_CO2/MWH',
        dependencies: [{ dependencyType: 'CARBON_FORMULA', versionId: null, status: 'MISSING', message: '原始依赖说明：/v1/carbon-rules，GJ，APPROVED' }],
      }],
      issues: [{ itemCode: 'ELECTRICITY_FACTOR', severity: 'BLOCKER', code: 'ELECTRICITY_YEAR_REQUIRED', message: '原始问题说明：/v1/carbon-rules，GJ，APPROVED' }],
    } as never)
    const wrapper = await mountPage()

    const packageSelect = wrapper.findAllComponents({ name: 'ElSelect' })
      .find(select => select.attributes('data-test') === 'parameter-package')
    if (!packageSelect) throw new Error('Missing parameter package selector')
    packageSelect.vm.$emit('update:modelValue', 'B02')
    await flushPromises()
    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('[data-test="parameter-preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="preview-issue-action"]').text()).toBe('分别填写资料数据年和核算年度后重新预检。')
    expect(wrapper.find('[data-test="preview-dependencies"]').text()).toContain('碳排放计算公式')
    expect(wrapper.find('[data-test="preview-dependencies"]').text()).toContain('请先选择或维护覆盖本次适用期的已审核版本。')
    expect(wrapper.find('[data-test="preview-issue-action"]').text()).not.toContain('/v1/carbon-rules')
    expect(wrapper.find('[data-test="preview-dependencies"]').text()).not.toContain('/v1/carbon-rules')
    expect(wrapper.find('[data-test="preview-issue-technical"]').attributes('open')).toBeUndefined()
    expect(wrapper.find('[data-test="preview-issue-technical"]').text()).toContain('/v1/carbon-rules，GJ，APPROVED')
    expect(wrapper.find('[data-test="preview-dependency-technical"]').text()).toContain('/v1/carbon-rules，GJ，APPROVED')

    parameterWorkspace.preview.mockResolvedValueOnce({
      packageCode: 'B02', readyForImport: false, items: [],
      issues: [{ itemCode: 'ELECTRICITY_FACTOR', severity: 'BLOCKER', code: 'UNRECOGNIZED_SERVER_RULE', message: '诊断原文：/v1/carbon-rules/future，APPROVED' }],
    } as never)
    await wrapper.find('[data-test="parameter-preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="preview-issue-action"]').text()).toContain('服务端返回未识别的校验结果')
    expect(wrapper.find('[data-test="preview-issue-action"]').text()).not.toContain('/v1/carbon-rules/future')
    expect(wrapper.find('[data-test="preview-issue-technical"]').text()).toContain('/v1/carbon-rules/future，APPROVED')
    wrapper.unmount()
  })
})
