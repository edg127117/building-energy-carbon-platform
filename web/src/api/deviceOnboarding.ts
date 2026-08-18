import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'
import type {
  DeviceBindRequest,
  DeviceBindResult,
  DeviceProductDetail,
  DeviceProductForm,
  DeviceProductListItem,
  DeviceProductQuery,
  IdentityStatusResult,
  PageResponse,
  PendingDeviceDetail,
  PendingDeviceListItem,
  PendingDeviceQuery,
} from '@/types/deviceOnboarding'

const PRODUCT_PATH = '/v1/device-products'
const ONBOARDING_PATH = '/v1/device-onboarding'
const data = <T>(response: { data: ApiResult<T> }): T => response.data.data
const pathId = (id: string) => encodeURIComponent(id)

/** 设备接入客户端只暴露版本化 DTO，不把 Axios 响应或服务端实体带入页面。 */
export async function pageDeviceProducts(query: DeviceProductQuery): Promise<PageResponse<DeviceProductListItem>> {
  return data(await http.get<ApiResult<PageResponse<DeviceProductListItem>>>(PRODUCT_PATH, { params: query }))
}

export async function getDeviceProduct(productId: string): Promise<DeviceProductDetail> {
  return data(await http.get<ApiResult<DeviceProductDetail>>(`${PRODUCT_PATH}/${pathId(productId)}`))
}

export async function createDeviceProduct(request: DeviceProductForm): Promise<DeviceProductDetail> {
  return data(await http.post<ApiResult<DeviceProductDetail>>(PRODUCT_PATH, request))
}

export async function updateDeviceProduct(productId: string, request: Omit<DeviceProductForm, 'productCode'>): Promise<DeviceProductDetail> {
  return data(await http.put<ApiResult<DeviceProductDetail>>(`${PRODUCT_PATH}/${pathId(productId)}`, request))
}

export async function copyDeviceProduct(productId: string, request: { productCode: string; productName: string }): Promise<DeviceProductDetail> {
  return data(await http.post<ApiResult<DeviceProductDetail>>(`${PRODUCT_PATH}/${pathId(productId)}/copy`, request))
}

export async function enableDeviceProduct(productId: string): Promise<DeviceProductDetail> {
  return data(await http.post<ApiResult<DeviceProductDetail>>(`${PRODUCT_PATH}/${pathId(productId)}/enable`))
}

export async function disableDeviceProduct(productId: string): Promise<DeviceProductDetail> {
  return data(await http.post<ApiResult<DeviceProductDetail>>(`${PRODUCT_PATH}/${pathId(productId)}/disable`))
}

export async function pagePendingDevices(query: PendingDeviceQuery): Promise<PageResponse<PendingDeviceListItem>> {
  return data(await http.get<ApiResult<PageResponse<PendingDeviceListItem>>>(`${ONBOARDING_PATH}/pending`, { params: query }))
}

export async function getPendingDevice(pendingId: string): Promise<PendingDeviceDetail> {
  return data(await http.get<ApiResult<PendingDeviceDetail>>(`${ONBOARDING_PATH}/pending/${pathId(pendingId)}`))
}

export async function updatePendingDeviceStatus(pendingId: string, request: { status: 'DISCOVERED' | 'IGNORED'; reason?: string | null }): Promise<PendingDeviceDetail> {
  return data(await http.put<ApiResult<PendingDeviceDetail>>(`${ONBOARDING_PATH}/pending/${pathId(pendingId)}/status`, request))
}

export async function bindPendingDevice(pendingId: string, request: DeviceBindRequest): Promise<DeviceBindResult> {
  return data(await http.post<ApiResult<DeviceBindResult>>(`${ONBOARDING_PATH}/pending/${pathId(pendingId)}/bind`, request))
}

export async function activateDeviceIdentity(identityId: string): Promise<IdentityStatusResult> {
  return data(await http.post<ApiResult<IdentityStatusResult>>(`${ONBOARDING_PATH}/identities/${pathId(identityId)}/activate`))
}

export async function deactivateDeviceIdentity(identityId: string): Promise<IdentityStatusResult> {
  return data(await http.post<ApiResult<IdentityStatusResult>>(`${ONBOARDING_PATH}/identities/${pathId(identityId)}/deactivate`))
}
