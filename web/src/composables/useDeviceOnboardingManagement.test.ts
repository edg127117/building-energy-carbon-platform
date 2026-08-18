import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  activateDeviceIdentity,
  bindPendingDevice,
  getPendingDevice,
  pageDeviceProducts,
  pagePendingDevices,
} from '@/api/deviceOnboarding'
import { useDeviceOnboardingManagement } from './useDeviceOnboardingManagement'
import type { DeviceBindRequest, PageResponse, PendingDeviceListItem } from '@/types/deviceOnboarding'

vi.mock('@/api/deviceOnboarding', () => ({
  activateDeviceIdentity: vi.fn(), bindPendingDevice: vi.fn(), copyDeviceProduct: vi.fn(), createDeviceProduct: vi.fn(), disableDeviceProduct: vi.fn(), enableDeviceProduct: vi.fn(),
  getDeviceProduct: vi.fn(), getPendingDevice: vi.fn(), pageDeviceProducts: vi.fn(), pagePendingDevices: vi.fn(), updateDeviceProduct: vi.fn(), updatePendingDeviceStatus: vi.fn(),
}))
vi.mock('@/utils/request', () => ({ requestErrorMessage: (reason: unknown) => (reason as { response?: { status?: number } })?.response?.status === 403 ? '当前账号没有权限执行此操作' : '请求失败，请稍后重试' }))

const emptyProducts = { page: 1, size: 20, total: 0, items: [] }
const emptyPending: PageResponse<PendingDeviceListItem> = { page: 1, size: 20, total: 0, items: [] }

describe('device onboarding asynchronous ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(pageDeviceProducts).mockResolvedValue(emptyProducts)
    vi.mocked(pagePendingDevices).mockResolvedValue(emptyPending)
  })

  it('keeps the latest pending query when an earlier response arrives late', async () => {
    const first = deferred<PageResponse<PendingDeviceListItem>>()
    vi.mocked(pagePendingDevices).mockReturnValueOnce(first.promise).mockResolvedValueOnce(page('latest'))
    const management = useDeviceOnboardingManagement()
    const stale = management.loadPending()
    await management.setPendingQuery({ status: 'DISCOVERED' })
    first.resolve(page('stale'))
    await stale
    expect(management.pendingPage.value.items[0].pendingId).toBe('latest')
  })

  it('locks duplicate binding and keeps the separate activation result', async () => {
    const request: DeviceBindRequest = { productId: 'P-01', buildingId: 'B-01', spaceId: 'S-01', systemGroupId: 'G-01', existingEquipmentId: 'E-01', pointBindings: [{ metricCode: 'temp', existingPointId: 'POINT-01' }] }
    vi.mocked(bindPendingDevice).mockResolvedValue({ pendingId: 'D-01', identityId: 'I-01', equipmentId: 'E-01', pointIds: ['POINT-01'], status: 'BOUND', configEffective: true })
    vi.mocked(getPendingDevice).mockResolvedValue({ pendingId: 'D-01', identityType: 'CLIENT_ID', identityValue: 'device-01', profileCode: 'HVAC_V1', lastProfileVersion: 1, status: 'BOUND', boundIdentityId: 'I-01', reportCount: 1, firstSeenTime: 1, lastSeenTime: 1, latestEventTime: 1, latestTimeSource: 'DEVICE', latestMetrics: {}, sampleTruncated: false, allowedActions: [] })
    vi.mocked(activateDeviceIdentity).mockResolvedValue({ identityId: 'I-01', status: 'ACTIVE', configEffective: true })
    const management = useDeviceOnboardingManagement()
    await Promise.all([management.bind('D-01', request), management.bind('D-01', request)])
    expect(bindPendingDevice).toHaveBeenCalledTimes(1)
    expect(management.bindResult.value?.status).toBe('BOUND')
    expect(management.activationResult.value).toBeNull()
    await management.activate('I-01')
    expect(management.activationResult.value).toEqual({ identityId: 'I-01', status: 'ACTIVE', configEffective: true })
  })

  it('marks a 403 as a permission state instead of an empty discovery list', async () => {
    vi.mocked(pagePendingDevices).mockRejectedValueOnce({ response: { status: 403 } })
    const management = useDeviceOnboardingManagement()
    await expect(management.loadPending()).rejects.toMatchObject({ response: { status: 403 } })
    expect(management.pendingError.value).toEqual({ message: '当前账号没有权限执行此操作', forbidden: true })
  })
})

function page(pendingId: string): PageResponse<PendingDeviceListItem> {
  return { page: 1, size: 20, total: 1, items: [{ pendingId, identityType: 'CLIENT_ID', maskedIdentityValue: 'de***01', profileCode: 'HVAC_V1', lastProfileVersion: 1, status: 'DISCOVERED', reportCount: 2, firstSeenTime: 1, lastSeenTime: 2, sampleTruncated: false }] }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done }); return { promise, resolve } }
