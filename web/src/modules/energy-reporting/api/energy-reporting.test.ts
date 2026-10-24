import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi, requestFile } from '@/infrastructure/http/public'
import { energyReportingApi } from './energy-reporting'
import type { ReportingRow, ReportingSourceConfiguration, ReportingWorkbookMappingRequest } from '../models/reporting'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn(), requestFile: vi.fn() }))

const sourceConfiguration: ReportingSourceConfiguration = {
  name: '十月电费账单',
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
  reason: '新增账单来源',
  conversionMethod: null,
  conversionPerspective: null,
  regionCode: null,
  supersedesSourceId: 'S/OLD',
}

const row: ReportingRow = {
  sourceId: 'S/01',
  sourceRevision: 2,
  quantity: '123.45',
  valueStatus: 'CONFIRMED',
  dataNature: 'NATIVE_QUANTITY',
  startDate: '2026-10-01',
  endDate: '2026-11-01',
  evidence: 'BILL-202610',
  note: null,
  exclusionQuantity: null,
  exclusionReason: null,
  exclusionEvidence: null,
}

describe('能源账单填报接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
    vi.mocked(requestFile).mockResolvedValue(new Blob() as never)
  })

  it('编码路径中的业务标识，并保留上下文月份查询参数', async () => {
    await energyReportingApi.context('B/01', '2026-10-01')
    await energyReportingApi.getBatch('B/01', 'BATCH/01')

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'get',
      url: '/v1/energy-reporting/buildings/B%2F01/context',
      params: { month: '2026-10-01' },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({
      method: 'get',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01',
    }))
  })

  it('把来源替代关系和创建幂等键作为正式请求体发送', async () => {
    await energyReportingApi.createSource('B/01', { configuration: sourceConfiguration, idempotencyKey: 'source-key' })
    await energyReportingApi.updateSource('B/01', 'S/01', { expectedRevision: 3, configuration: sourceConfiguration })
    await energyReportingApi.transitionSource('B/01', 'S/01', 'approve', { expectedRevision: 4, reason: '审核通过' })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/sources',
      data: { configuration: sourceConfiguration, idempotencyKey: 'source-key' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'put',
      url: '/v1/energy-reporting/buildings/B%2F01/sources/S%2F01',
      data: { expectedRevision: 3, configuration: sourceConfiguration },
    })
    expect(requestApi).toHaveBeenNthCalledWith(3, {
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/sources/S%2F01/approve',
      data: { expectedRevision: 4, reason: '审核通过' },
    })
  })

  it('保留月行数据性质、修订号和批次命令幂等键', async () => {
    await energyReportingApi.createBatch('B/01', {
      month: '2026-10-01',
      reason: '创建十月填报',
      idempotencyKey: 'batch-create-key',
      supersedesBatchId: null,
    })
    await energyReportingApi.updateBatch('B/01', 'BATCH/01', {
      expectedRevision: 5,
      rows: [{ ...row, dataNature: 'AMOUNT_ESTIMATE' }],
      reason: '保存金额估算草稿',
    })
    await energyReportingApi.previewBatch('B/01', 'BATCH/01')
    await energyReportingApi.transitionBatch('B/01', 'BATCH/01', 'reject', {
      expectedRevision: 6,
      reason: '需要补充凭证',
      idempotencyKey: 'batch-transition-key',
    })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/batches',
      data: {
        month: '2026-10-01',
        reason: '创建十月填报',
        idempotencyKey: 'batch-create-key',
        supersedesBatchId: null,
      },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'put',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01',
      data: {
        expectedRevision: 5,
        rows: [{ ...row, dataNature: 'AMOUNT_ESTIMATE' }],
        reason: '保存金额估算草稿',
      },
    })
    expect(requestApi).toHaveBeenNthCalledWith(3, {
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01/preview',
    })
    expect(requestApi).toHaveBeenNthCalledWith(4, {
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01/reject',
      data: { expectedRevision: 6, reason: '需要补充凭证', idempotencyKey: 'batch-transition-key' },
    })
  })

  it('下载模板并以 multipart 上传工作簿预览，不在浏览器解析或执行公式', async () => {
    const file = new File(['workbook'], 'monthly.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    await energyReportingApi.workbookTemplate('B/01', 'BATCH/01')
    await energyReportingApi.previewWorkbook('B/01', 'BATCH/01', file)

    expect(requestFile).toHaveBeenCalledWith({
      method: 'get',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01/workbook-template',
    })
    const call = vi.mocked(requestApi).mock.calls.at(-1)![0]
    expect(call).toMatchObject({
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01/workbook-preview',
    })
    expect(call.headers).toBeUndefined()
    const data = call.data as FormData
    expect(data.get('file')).toEqual(file)
    expect(data.get('mapping')).toBeNull()
  })

  it('将实施人员选择的单元格映射作为 multipart 参数发送', async () => {
    const file = new File(['workbook'], 'legacy.xlsx')
    const mapping: ReportingWorkbookMappingRequest = {
      format: 'CELL_MAPPING',
      mappings: [{
        sourceId: 'S/01', sheetName: '十月账单', quantityCell: 'C12', unitCode: 'KWH',
        startDate: '2026-10-01', endDate: '2026-11-01', dataNature: 'NATIVE_QUANTITY', evidence: 'BILL-202610',
      }],
    }

    await energyReportingApi.previewWorkbook('B/01', 'BATCH/01', file, mapping)

    const call = vi.mocked(requestApi).mock.calls.at(-1)![0]
    expect(call).toMatchObject({
      method: 'post',
      url: '/v1/energy-reporting/buildings/B%2F01/batches/BATCH%2F01/workbook-preview',
    })
    expect(call.headers).toBeUndefined()
    const data = call.data as FormData
    expect(data.get('file')).toEqual(file)
    expect(data.get('mapping')).toBe(JSON.stringify(mapping))
  })
})
