import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import {
  copyDeviceProduct,
  createHvacTemperatureBatchJob,
  createHvacTemperatureInitializationJob,
  createHvacTemperatureRuleRequest,
  getDaikinDirectorySync,
  getHvacTemperatureBatchJob,
  getLatestHvacTemperatureBatchJob,
  getHvacTemperatureBindingOptions,
  getOperationsPendingDevice,
  getOperationsCompatibleProduct,
  getOperationsBindingOptions,
  getPendingDeviceConnection,
  listDeviceProducts,
  listDaikinDirectorySyncJobs,
  listDaikinSources,
  listOperationsCompatibleProducts,
  listOperationsPendingDevices,
  listOperationsNumericSources,
  listPendingDevices,
  listPointNamingRules,
  requestDaikinDirectorySync,
  retryHvacTemperatureBatchJob,
  previewHvacTemperatureBindings,
  previewHvacTemperatureInitialization,
  submitOperationsBinding,
  submitOperationsBindingBatch,
  submitOperationsIdentityStatus,
  updatePendingStatus,
} from './onboarding'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('设备接入接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('通过统一请求层查询产品模板和待接入设备', async () => {
    await listDeviceProducts({ page: 1, size: 20, status: 'ENABLED', keyword: '空调', expectedProfileCode: 'V1', identityType: 'SN' })
    await listPendingDevices({ page: 2, size: 20, status: 'DISCOVERED', identity: 'dev', profileCode: 'V1' })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'get',
      url: '/v1/device-products',
      params: { page: 1, size: 20, status: 'ENABLED', keyword: '空调', expectedProfileCode: 'V1', identityType: 'SN' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'get',
      url: '/v1/device-onboarding/pending',
      params: { page: 2, size: 20, status: 'DISCOVERED', identity: 'dev', profileCode: 'V1' },
    })
  })

  it('读取待接入连接状态和后端启用命名规则', async () => {
    await getPendingDeviceConnection('D/01')
    await listPointNamingRules()

    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/device-onboarding/pending/D%2F01/connection' })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'get', url: '/v1/device-onboarding/naming-rules' })
  })

  it('保留可直接编辑的草稿与待处理状态接口，不登记已拒绝的敏感直写接口', async () => {
    await copyDeviceProduct('P/01', { productCode: 'P-02', productName: '副本' })
    await updatePendingStatus('D/01', { status: 'IGNORED', reason: null })
    const urls = vi.mocked(requestApi).mock.calls.map(([config]) => config.url)

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post',
      url: '/v1/device-products/P%2F01/copy',
      data: { productCode: 'P-02', productName: '副本' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'put',
      url: '/v1/device-onboarding/pending/D%2F01/status',
      data: { status: 'IGNORED', reason: null },
    })
    expect(urls.some(url => url?.includes('/bind') || url?.includes('/activate') || url?.includes('/deactivate'))).toBe(false)
  })

  it('使用范围受控运维接口读取厂家目录和提交绑定申请', async () => {
    const binding = {
      productId: 'P-01', buildingId: 'B-01', spaceId: 'S-01', systemGroupId: 'G-01',
      existingEquipmentId: 'E-01', newEquipment: null, pointBindings: [],
    }
    await listOperationsPendingDevices({ page: 1, size: 20, status: 'DISCOVERED' })
    await getOperationsPendingDevice('D/01')
    await listOperationsCompatibleProducts('D/01', { page: 2, size: 20 })
    await getOperationsCompatibleProduct('D/01', 'P/01')
    await listOperationsNumericSources('D/01')
    await getOperationsBindingOptions('D/01', { page: 2, size: 20, spaceId: 'S-01', systemGroupId: 'G-01', productId: 'P-01' })
    await submitOperationsBinding('D/01', binding, 'stable-1')
    await submitOperationsBindingBatch([{ pendingId: 'D/01', binding, idempotencyKey: 'stable-1' }])
    await submitOperationsIdentityStatus('D/01', 'ACTIVE', 'stable-enable')

    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/operations/device-onboarding/pending', params: { page: 1, size: 20, status: 'DISCOVERED' } })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'get', url: '/v1/operations/device-onboarding/pending/D%2F01' })
    expect(requestApi).toHaveBeenNthCalledWith(3, { method: 'get', url: '/v1/operations/device-onboarding/pending/D%2F01/products', params: { page: 2, size: 20 } })
    expect(requestApi).toHaveBeenNthCalledWith(4, { method: 'get', url: '/v1/operations/device-onboarding/pending/D%2F01/products/P%2F01' })
    expect(requestApi).toHaveBeenNthCalledWith(5, { method: 'get', url: '/v1/operations/device-onboarding/pending/D%2F01/numeric-sources' })
    expect(requestApi).toHaveBeenNthCalledWith(6, { method: 'get', url: '/v1/operations/device-onboarding/pending/D%2F01/binding-options', params: { page: 2, size: 20, spaceId: 'S-01', systemGroupId: 'G-01', productId: 'P-01' } })
    expect(requestApi).toHaveBeenNthCalledWith(7, { method: 'post', url: '/v1/operations/device-onboarding/pending/D%2F01/binding-requests', data: { binding, idempotencyKey: 'stable-1' } })
    expect(requestApi).toHaveBeenNthCalledWith(8, { method: 'post', url: '/v1/operations/device-onboarding/binding-requests/batch', data: { items: [{ pendingId: 'D/01', binding, idempotencyKey: 'stable-1' }] } })
    expect(requestApi).toHaveBeenNthCalledWith(9, { method: 'post', url: '/v1/operations/device-onboarding/pending/D%2F01/identity-status-requests', data: { targetStatus: 'ACTIVE', idempotencyKey: 'stable-enable' } })
  })

  it('提交并查询厂家异步同步任务，不发送凭据或清单', async () => {
    await listDaikinSources()
    await requestDaikinDirectorySync('source/A')
    await getDaikinDirectorySync('source/A', 'job/1')
    await listDaikinDirectorySyncJobs({ page: 2, size: 10 })

    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/daikin/sources' })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'post', url: '/v1/daikin/sources/source%2FA/sync-jobs' })
    expect(requestApi).toHaveBeenNthCalledWith(3, { method: 'get', url: '/v1/daikin/sources/source%2FA/sync-jobs/job%2F1' })
    expect(requestApi).toHaveBeenNthCalledWith(4, { method: 'get', url: '/v1/daikin/sync-jobs', params: { page: 2, size: 10 } })
  })

  it('温度绑定使用服务端预览摘要、持久批量任务和审批规则接口', async () => {
    const binding = {
      productId: 'P-01', buildingId: 'B-01', spaceId: 'S-01', systemGroupId: 'G-01',
      existingEquipmentId: null, newEquipment: { equipmentName: '大金内机-B303', manufacturer: '大金' }, pointBindings: [],
      temperatureMode: 'AUTO' as const,
    }
    await getHvacTemperatureBindingOptions('D/01')
    await previewHvacTemperatureBindings([{ pendingId: 'D/01', mode: 'AUTO', binding }])
    await createHvacTemperatureBatchJob('stable-temperature', [{ pendingId: 'D/01', mode: 'AUTO', numericSourceId: 'SRC-01', digest: 'digest-01' }])
    await getHvacTemperatureBatchJob('job/1')
    await getLatestHvacTemperatureBatchJob('D/01')
    await retryHvacTemperatureBatchJob('job/1', ['D/01'])
    await createHvacTemperatureRuleRequest({
      idempotencyKey: 'stable-rule',
      rule: { adapterId: 'DAIKIN_INDOOR_V2', buildingId: 'B-01', sourceScope: '', model: '', templateProductId: 'TP-01', numericSourceId: 'SRC-01', revision: 0, enabled: true },
    })

    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/operations/hvac-temperature-bindings/pending/D%2F01/options' })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/preview', data: { items: [{ pendingId: 'D/01', mode: 'AUTO', binding }] },
    })
    expect(requestApi).toHaveBeenNthCalledWith(3, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/batch-jobs',
      data: { idempotencyKey: 'stable-temperature', items: [{ pendingId: 'D/01', mode: 'AUTO', numericSourceId: 'SRC-01', digest: 'digest-01' }] },
    })
    expect(requestApi).toHaveBeenNthCalledWith(4, { method: 'get', url: '/v1/operations/hvac-temperature-bindings/batch-jobs/job%2F1' })
    expect(requestApi).toHaveBeenNthCalledWith(5, {
      method: 'get', url: '/v1/operations/hvac-temperature-bindings/batch-jobs/latest', params: { pendingId: 'D/01' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(6, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/batch-jobs/job%2F1/retry', data: { pendingIds: ['D/01'] },
    })
    expect(requestApi).toHaveBeenNthCalledWith(7, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/rule-requests',
      data: {
        idempotencyKey: 'stable-rule',
        rule: { adapterId: 'DAIKIN_INDOOR_V2', buildingId: 'B-01', sourceScope: '', model: '', templateProductId: 'TP-01', numericSourceId: 'SRC-01', revision: 0, enabled: true },
      },
    })
  })

  it('首次温度初始化使用独立预览和批次任务契约', async () => {
    await previewHvacTemperatureInitialization({ pendingIds: ['D1', 'D2'], templateProductId: 'T1' })
    await createHvacTemperatureInitializationJob({
      pendingIds: ['D1', 'D2'], templateProductId: 'T1', digest: 'batch-digest', idempotencyKey: 'init-1',
    })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/initialization/preview',
      data: { pendingIds: ['D1', 'D2'], templateProductId: 'T1' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'post', url: '/v1/operations/hvac-temperature-bindings/initialization/jobs',
      data: { pendingIds: ['D1', 'D2'], templateProductId: 'T1', digest: 'batch-digest', idempotencyKey: 'init-1' },
    })
  })
})
