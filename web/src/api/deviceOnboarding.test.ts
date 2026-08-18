import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/utils/request'
import {
  activateDeviceIdentity,
  bindPendingDevice,
  copyDeviceProduct,
  createDeviceProduct,
  getDeviceProduct,
  getPendingDevice,
  pageDeviceProducts,
  pagePendingDevices,
  updatePendingDeviceStatus,
} from './deviceOnboarding'

vi.mock('@/utils/request', () => ({ http: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))

const result = <T>(data: T) => ({ data: { success: true, code: 200, msg: 'ok', data } })

describe('device onboarding API contract client', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(http.get).mockResolvedValue(result({ page: 1, size: 20, total: 0, items: [] }))
    vi.mocked(http.post).mockResolvedValue(result({ identityId: 'I-01' }))
    vi.mocked(http.put).mockResolvedValue(result({ pendingId: 'P-01' }))
  })

  it('uses versioned product endpoints and unwraps page DTOs', async () => {
    await expect(pageDeviceProducts({ page: 1, size: 20, status: 'ENABLED' })).resolves.toMatchObject({ total: 0, items: [] })
    await getDeviceProduct('PRODUCT/01')
    await createDeviceProduct({ productCode: 'P01', productName: '产品', equipmentTypeCode: 'AHU', expectedProfileCode: 'HVAC_V1', identityType: 'CLIENT_ID', points: [] })
    await copyDeviceProduct('PRODUCT/01', { productCode: 'P02', productName: '产品副本' })
    expect(http.get).toHaveBeenNthCalledWith(1, '/v1/device-products', { params: { page: 1, size: 20, status: 'ENABLED' } })
    expect(http.get).toHaveBeenNthCalledWith(2, '/v1/device-products/PRODUCT%2F01')
    expect(http.post).toHaveBeenCalledWith('/v1/device-products', expect.objectContaining({ productCode: 'P01' }))
    expect(http.post).toHaveBeenCalledWith('/v1/device-products/PRODUCT%2F01/copy', { productCode: 'P02', productName: '产品副本' })
  })

  it('keeps pending, binding and activation payloads on their exact paths', async () => {
    await pagePendingDevices({ page: 2, size: 20, status: 'DISCOVERED', identity: 'device' })
    await getPendingDevice('P/01')
    await updatePendingDeviceStatus('P/01', { status: 'IGNORED', reason: '暂不接入' })
    const bindRequest = { productId: 'PRODUCT-01', buildingId: 'B-01', spaceId: 'S-01', systemGroupId: 'G-01', existingEquipmentId: 'E-01', pointBindings: [{ metricCode: 'temp', existingPointId: 'POINT-01' }] }
    await bindPendingDevice('P/01', bindRequest)
    await activateDeviceIdentity('I/01')
    expect(http.get).toHaveBeenCalledWith('/v1/device-onboarding/pending', { params: { page: 2, size: 20, status: 'DISCOVERED', identity: 'device' } })
    expect(http.get).toHaveBeenCalledWith('/v1/device-onboarding/pending/P%2F01')
    expect(http.put).toHaveBeenCalledWith('/v1/device-onboarding/pending/P%2F01/status', { status: 'IGNORED', reason: '暂不接入' })
    expect(http.post).toHaveBeenCalledWith('/v1/device-onboarding/pending/P%2F01/bind', bindRequest)
    expect(http.post).toHaveBeenCalledWith('/v1/device-onboarding/identities/I%2F01/activate')
  })
})
