import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReportingBatch, ReportingCapabilities, ReportingContext, ReportingSourceConfiguration } from '../models/reporting'

const routeState = vi.hoisted(() => ({
  query: {} as Record<string, string>,
  replace: vi.fn(),
  push: vi.fn(),
}))
const reportingApi = vi.hoisted(() => ({ context: vi.fn(), createBatch: vi.fn(), transitionBatch: vi.fn() }))
const buildingsApi = vi.hoisted(() => ({ listReportingBuildings: vi.fn() }))

vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => ({ user: { id: 1, roles: ['ENERGY_MANAGER'] } }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/operations/energy/energyItems', query: routeState.query }),
  useRouter: () => ({ replace: routeState.replace, push: routeState.push }),
}))
vi.mock('../api/energy-reporting', () => ({ energyReportingApi: reportingApi }))
vi.mock('../api/buildings', () => ({ listReportingBuildings: buildingsApi.listReportingBuildings }))

import EnergyReportingPage from './EnergyReportingPage.vue'

const sourceConfiguration: ReportingSourceConfiguration = {
  name: '物业电费账单',
  energyItemCode: 'ELECTRICITY',
  unitCode: 'KWH',
  channel: 'BILL',
  pathway: 'PURCHASED_ELECTRICITY',
  purpose: 'PRIMARY',
  coverage: 'WHOLE_BUILDING',
  meterOverlap: 'NO_METER',
  timezoneId: 'Asia/Shanghai',
  effectiveFrom: '2026-10-01',
  effectiveTo: null,
  evidence: 'BILL-202610',
  reason: '来源已批准',
  conversionMethod: null,
  conversionPerspective: null,
  regionCode: null,
  supersedesSourceId: null,
}

const fullCapabilities: ReportingCapabilities = {
  maintainSources: true,
  reviewSources: true,
  reviewBatches: true,
  sealBatches: true,
}

function context(status: ReportingBatch['status'] = 'DRAFT', capabilities = fullCapabilities, createdBy = 1): ReportingContext {
  return {
    buildingId: 'B-1',
    month: '2026-10-01',
    sources: [{ sourceId: 'S-1', revision: 1, status: 'APPROVED', createdBy, reviewedBy: 2, configuration: sourceConfiguration }],
    batches: [{
      batchId: 'BATCH-1', buildingId: 'B-1', month: '2026-10-01', status, revision: 3, createdBy, reviewedBy: null,
      supersedesBatchId: null,
      rows: [{
        sourceId: 'S-1', sourceRevision: 1, quantity: '100', valueStatus: 'CONFIRMED', dataNature: 'NATIVE_QUANTITY',
        startDate: '2026-10-01', endDate: '2026-11-01', evidence: 'BILL-202610', note: null,
        exclusionQuantity: null, exclusionReason: null, exclusionEvidence: null,
      }],
      snapshot: null,
    }],
    energyOptions: [{ code: 'ELECTRICITY', name: '电力', usageScopes: ['PURCHASED_ELECTRICITY'] }],
    unitOptions: [{ code: 'KWH', symbol: 'kWh', dimension: 'ENERGY' }],
    conversionOptions: [{
      energyItemCode: 'ELECTRICITY', method: 'STANDARD_COAL', perspective: 'LOCATION', regionCode: 'CN',
      label: '标准煤折算法 · 地区口径（CN）',
    }],
    capabilities,
  }
}

async function mountPage(value: ReportingContext, section: 'source' | 'batch' = 'batch') {
  routeState.query = { buildingId: 'B-1', month: '2026-10-01', section }
  reportingApi.context.mockResolvedValue(value)
  buildingsApi.listReportingBuildings.mockResolvedValue({ records: [{ buildingId: 'B-1', buildingName: '一号楼' }], total: 1 })
  const wrapper = mount(EnergyReportingPage, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('能源账单填报页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('月行被编辑后标记为未保存，并阻止直接预检', async () => {
    const wrapper = await mountPage(context())
    const quantity = wrapper.find('.batch-table input')

    expect(quantity.exists()).toBe(true)
    await quantity.setValue('120')
    await nextTick()

    expect(wrapper.find('[data-test="batch-save"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="batch-preview"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('审核能力只显示允许的批次动作，已审核批次可退回但不能再次通过', async () => {
    const wrapper = await mountPage(context('REVIEWED', { ...fullCapabilities, sealBatches: false }, 2))

    expect(wrapper.find('[data-test="batch-approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="batch-reject"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="batch-seal"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('当前用户不能编辑或审核自己创建的批次', async () => {
    const wrapper = await mountPage(context('SUBMITTED'))

    expect(wrapper.find('[data-test="batch-approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="batch-reject"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('在没有待办批次时基于封存批次创建修订草稿', async () => {
    const sealed = context('SEALED')
    reportingApi.createBatch.mockResolvedValue({ batchId: 'BATCH-2' })
    const wrapper = await mountPage(sealed)
    const page = wrapper.vm as unknown as { batchActionReason: string }

    expect(wrapper.get('[data-test="batch-create"]').text()).toContain('新建修订草稿')
    expect(wrapper.text()).toContain('原封存记录会保留')
    page.batchActionReason = '更正十月账单凭证'
    await nextTick()
    await wrapper.get('[data-test="batch-create"]').trigger('click')
    await flushPromises()

    expect(reportingApi.createBatch).toHaveBeenCalledWith('B-1', expect.objectContaining({
      month: '2026-10-01',
      reason: '更正十月账单凭证',
      supersedesBatchId: 'BATCH-1',
    }))
    wrapper.unmount()
  })

  it('空草稿可显式更新已审核来源行，仍需预检和独立审核', async () => {
    const value = context()
    value.batches[0].rows = []
    const wrapper = await mountPage(value)
    const refreshed = context()
    refreshed.batches[0].revision = 4
    refreshed.batches[0].rows[0].quantity = null
    refreshed.batches[0].rows[0].valueStatus = 'MISSING'
    reportingApi.transitionBatch.mockResolvedValue(refreshed.batches[0])
    reportingApi.context.mockResolvedValue(refreshed)
    const page = wrapper.vm as unknown as { batchActionReason: string }
    page.batchActionReason = '来源审批完成，更新空草稿'
    await nextTick()
    await wrapper.get('[data-test="batch-refresh-sources"]').trigger('click')
    await flushPromises()

    expect(reportingApi.transitionBatch).toHaveBeenCalledWith('B-1', 'BATCH-1', 'refresh-sources', expect.objectContaining({
      expectedRevision: 3, reason: '来源审批完成，更新空草稿',
    }))
    expect(wrapper.find('.batch-table input').exists()).toBe(true)
    expect(wrapper.find('[data-test="batch-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('更新来源不能改动已提交批次或绕过本人草稿及未保存修改保护', async () => {
    const wrapper = await mountPage(context('SUBMITTED', fullCapabilities, 2))
    expect(wrapper.find('[data-test="batch-refresh-sources"]').exists()).toBe(false)
    const page = wrapper.vm as unknown as { batchActionReason: string; transitionBatch: (action: string) => Promise<void> }
    page.batchActionReason = '不允许的动作'
    await page.transitionBatch('refresh-sources')
    expect(reportingApi.transitionBatch).not.toHaveBeenCalled()
    wrapper.unmount()

    const editable = await mountPage(context())
    await editable.find('.batch-table input').setValue('120')
    expect(editable.get('[data-test="batch-refresh-sources"]').attributes('disabled')).toBeDefined()
    editable.unmount()
  })

  it('工作簿超过 1 MiB 时在上传前拒绝', async () => {
    const wrapper = await mountPage(context())
    const page = wrapper.vm as unknown as { selectWorkbook: (file: { raw: File }) => void }
    const oversized = new File([new Uint8Array(1024 * 1024 + 1)], 'oversized.xlsx')

    page.selectWorkbook({ raw: oversized })
    await nextTick()

    expect(wrapper.text()).toContain('请选择不超过 1 MiB 的 .xlsx 文件。')
    wrapper.unmount()
  })

  it('已审核批次只展示后端冻结快照的标煤状态和依据版本', async () => {
    const value = context('REVIEWED', fullCapabilities, 2)
    value.batches[0].snapshot = {
      contractVersion: 'ENERGY_BILL_MONTH_V1',
      nativeStatus: 'COMPLETE',
      tceStatus: 'NOT_CALCULATED',
      evidenceHash: 'snapshot-hash',
      rows: [{
        input: value.batches[0].rows[0],
        source: value.sources[0],
        nativeQuantity: '100',
        nativeUnit: 'KWH',
        energyItemVersionId: 'ITEM-V1',
        unitVersionId: 'UNIT-V1',
        unitEvidence: { versionId: 'UNIT-V1' },
        tce: null,
        tceStatus: 'NOT_CALCULATED',
        conversionEvidence: { parameterVersionId: 'PARAM-V1', formulaVersionId: 'FORMULA-V1', standardCoalVersionId: null },
      }],
    }
    const wrapper = await mountPage(value)

    expect(wrapper.find('[data-test="frozen-snapshot"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('未计算')
    expect(wrapper.text()).toContain('PARAM-V1')
    wrapper.unmount()
  })

  it('没有来源维护权限时不显示新增来源入口', async () => {
    const wrapper = await mountPage(context('DRAFT', { ...fullCapabilities, maintainSources: false }), 'source')

    expect(wrapper.find('[data-test="source-add"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('碳核算入口携带当前建筑和月份，交由既有后端核算页处理', async () => {
    const wrapper = await mountPage(context())

    await wrapper.find('[data-test="carbon-results-entry"]').trigger('click')

    expect(routeState.push).toHaveBeenCalledWith({
      path: '/operations/energy/energyZones',
      query: { buildingId: 'B-1', reportingMonth: '2026-10-01', tab: 'carbonResults' },
    })
    wrapper.unmount()
  })
})
