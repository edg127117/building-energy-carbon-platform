import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DaikinSyncJob, OnboardingPage, PendingDevice } from '../models/onboarding'
import { getOperationsPendingDevice, listDaikinDirectorySyncJobs, listDaikinSources, listEquipmentTypes, getPendingDeviceConnection, listDeviceProducts, listOperationsPendingDevices, listPendingDevices, listPointNamingRules, updatePendingStatus } from '../api/onboarding'
import { useDeviceOnboarding } from './use-device-onboarding'

vi.mock('../api/onboarding', () => ({
  copyDeviceProduct: vi.fn(),
  createDeviceProduct: vi.fn(),
  getDeviceProduct: vi.fn(),
  getPendingDevice: vi.fn(),
  getPendingDeviceConnection: vi.fn(),
  getOperationsPendingConnection: vi.fn(),
  getOperationsPendingDevice: vi.fn(),
  getOperationsCompatibleProduct: vi.fn(),
  getOperationsBindingOptions: vi.fn(),
  getDaikinDirectorySync: vi.fn(),
  listDeviceProducts: vi.fn(),
  listDaikinDirectorySyncJobs: vi.fn(),
  listDaikinSources: vi.fn(),
  listEquipmentTypes: vi.fn(),
  listPendingDevices: vi.fn(),
  listOperationsCompatibleProducts: vi.fn(),
  listOperationsPendingDevices: vi.fn(),
  listOperationsNumericSources: vi.fn(),
  listPointNamingRules: vi.fn(),
  updateDeviceProduct: vi.fn(),
  updatePendingStatus: vi.fn(),
  requestDaikinDirectorySync: vi.fn(),
  submitOperationsBinding: vi.fn(),
  submitOperationsBindingBatch: vi.fn(),
  submitOperationsIdentityStatus: vi.fn(),
  updateOperationsPendingStatus: vi.fn(),
}))
vi.mock('@/shared/utils/request-error', () => ({ requestErrorMessage: () => '请求失败', requestErrorCode: () => null }))

const emptyPending: OnboardingPage<PendingDevice> = { page: 1, size: 20, total: 0, items: [] }

describe('设备接入异步状态', () => {
  it('类型查询失败显示错误，重试后恢复有效选项', async () => {
    const management = useDeviceOnboarding()
    vi.mocked(listEquipmentTypes).mockRejectedValueOnce(new Error('transport'))
    await management.loadEquipmentTypes()
    expect(management.equipmentTypesError.value?.message).toBe('请求失败')
    expect(management.equipmentTypesLoading.value).toBe(false)
    vi.mocked(listEquipmentTypes).mockResolvedValueOnce([{ typeCode: 'ODU', typeName: '空调外机' }])
    await management.loadEquipmentTypes()
    expect(management.equipmentTypes.value[0]?.typeCode).toBe('ODU')
    expect(management.equipmentTypesError.value).toBeNull()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listPendingDevices).mockResolvedValue(emptyPending)
    vi.mocked(updatePendingStatus).mockResolvedValue({} as never)
    vi.mocked(listDeviceProducts).mockResolvedValue({ page: 1, size: 20, total: 0, items: [] })
    vi.mocked(getPendingDeviceConnection).mockResolvedValue({ pendingId: 'D-01', identityId: null, identityStatus: 'UNBOUND', equipmentId: null, buildingId: null, productId: null, configEffective: false })
    vi.mocked(listPointNamingRules).mockResolvedValue([])
    vi.mocked(listDaikinDirectorySyncJobs).mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
    vi.mocked(listDaikinSources).mockResolvedValue([])
  })

  it('加载当前账号可用的大金数据源名称', async () => {
    vi.mocked(listDaikinSources).mockResolvedValueOnce([{ sourceId: 'source-a', sourceName: '创新港大金空调' }])
    const management = useDeviceOnboarding({ operations: true })

    await management.loadDaikinSources()

    expect(management.daikinSources.value).toEqual([{ sourceId: 'source-a', sourceName: '创新港大金空调' }])
  })

  it('忽略迟到的待处理列表响应，只保留最新条件结果', async () => {
    const stale = deferred<OnboardingPage<PendingDevice>>()
    vi.mocked(listPendingDevices)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(page('latest'))
    const management = useDeviceOnboarding()

    const firstLoad = management.loadPendingDevices()
    await management.setPendingQuery({ status: 'DISCOVERED' })
    stale.resolve(page('stale'))
    await firstLoad

    expect(management.pendingDevices.value.items[0]?.pendingId).toBe('latest')
  })

  it('同一待处理状态变更在刷新完成前只会发送一次', async () => {
    const management = useDeviceOnboarding()

    await Promise.all([
      management.changePendingStatus('D-01', 'IGNORED'),
      management.changePendingStatus('D-01', 'IGNORED'),
    ])

    expect(updatePendingStatus).toHaveBeenCalledTimes(1)
    expect(listPendingDevices).toHaveBeenCalledTimes(1)
    expect(management.running.value.size).toBe(0)
  })

  it('将待处理状态写入失败保留为受控错误状态', async () => {
    vi.mocked(updatePendingStatus).mockRejectedValueOnce(new Error('transport'))
    const management = useDeviceOnboarding()

    await expect(management.changePendingStatus('D-01', 'IGNORED')).rejects.toThrow('transport')

    expect(management.operationError.value).toEqual({ message: '请求失败', code: null })
  })

  it('按待接入协议和身份精确过滤产品', async () => {
    const management = useDeviceOnboarding()
    await management.setProductQuery({ status: 'ENABLED', expectedProfileCode: 'V1', identityType: 'SN', keyword: '电表' })
    expect(listDeviceProducts).toHaveBeenCalledWith({ page: 1, size: 20, status: 'ENABLED', keyword: '电表', expectedProfileCode: 'V1', identityType: 'SN' })
  })

  it('连接状态请求使用代次隔离迟到响应', async () => {
    const stale = deferred<Awaited<ReturnType<typeof getPendingDeviceConnection>>>()
    vi.mocked(getPendingDeviceConnection).mockReturnValueOnce(stale.promise).mockResolvedValueOnce({ pendingId: 'new', identityId: null, identityStatus: 'UNBOUND', equipmentId: null, buildingId: null, productId: null, configEffective: false })
    const management = useDeviceOnboarding()
    const first = management.loadPendingConnection('old')
    await management.loadPendingConnection('new')
    stale.resolve({ pendingId: 'old', identityId: null, identityStatus: 'UNBOUND', equipmentId: null, buildingId: null, productId: null, configEffective: false })
    await first
    expect(management.pendingConnection.value?.pendingId).toBe('new')
  })

  it('运维模式只使用范围受控列表并拆分厂家目录详情', async () => {
    const location = { roomSpaceId: 'ROOM-303', roomCode: 'B303-1', monitorAddress: '1-01', assetReferenceCode: 'F000002' }
    const pendingPage = page('DAIKIN-01')
    pendingPage.items[0]!.location = location
    vi.mocked(listOperationsPendingDevices).mockResolvedValueOnce(pendingPage)
    vi.mocked(getOperationsPendingDevice).mockResolvedValueOnce({
      pending: {
        ...page('DAIKIN-01').items[0]!, identityValue: 'unit-01', boundIdentityId: null,
        latestEventTime: 0, latestTimeSource: null, latestMetrics: null, allowedActions: ['BIND'],
      },
      directory: {
        pendingId: 'DAIKIN-01', sourceId: 'source-1', siteId: 'site-1', controllerId: 'controller-1',
        kind: 'INDOOR', unitId: 'unit-01', siteName: '项目', deviceName: '内机', equipmentId: null,
        buildingId: 'B-01', missing: false, observedAt: '2026-09-17T00:00:00Z',
      },
      location,
    })
    const management = useDeviceOnboarding({ operations: true })

    await management.loadPendingDevices()
    await management.selectPending('DAIKIN-01')

    expect(listPendingDevices).not.toHaveBeenCalled()
    expect(management.selectedPending.value?.identityValue).toBe('unit-01')
    expect(management.selectedDirectory.value?.sourceId).toBe('source-1')
    expect(management.pendingDevices.value.items[0]?.location).toEqual(location)
    expect(management.selectedDirectory.value?.location).toEqual(location)
  })

  it('从持久化历史恢复最新同步任务并支持空历史', async () => {
    const latest: DaikinSyncJob = {
      jobId: 'job-2', sourceId: 'source-1', status: 'SUCCEEDED', attempts: 1, errorCode: null,
      createdAt: 20, updatedAt: 30, completedAt: 30,
    }
    vi.mocked(listDaikinDirectorySyncJobs).mockResolvedValueOnce({ page: 1, size: 10, total: 1, items: [latest] })
    const management = useDeviceOnboarding({ operations: true })

    await management.loadDirectorySyncJobs(1)
    expect(management.syncJob.value).toEqual(latest)
    expect(management.syncJobs.value.total).toBe(1)

    vi.mocked(listDaikinDirectorySyncJobs).mockResolvedValueOnce({ page: 1, size: 10, total: 0, items: [] })
    await management.loadDirectorySyncJobs(1)
    expect(management.syncJob.value).toBeNull()
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function page(pendingId: string): OnboardingPage<PendingDevice> {
  return {
    page: 1,
    size: 20,
    total: 1,
    items: [{
      pendingId,
      identityType: 'CLIENT_ID',
      maskedIdentityValue: 'de***01',
      profileCode: 'V1',
      lastProfileVersion: 1,
      status: 'DISCOVERED',
      reportCount: 0,
      firstSeenTime: 0,
      lastSeenTime: 0,
      sampleTruncated: false,
    }],
  }
}
