import { requestApi } from '@/infrastructure/http/public'
import type {
  DeviceProductDetail,
  DeviceProductForm,
  DeviceProductListItem,
  OnboardingPage,
  PendingDevice,
  PendingDeviceDetail,
  PendingStatusRequest,
} from '../models/onboarding'

const productPath = '/v1/device-products'
const onboardingPath = '/v1/device-onboarding'
const encoded = (value: string) => encodeURIComponent(value)

export function listDeviceProducts(params: { page: number; size: number; status?: string; keyword?: string }) {
  return requestApi<OnboardingPage<DeviceProductListItem>>({ method: 'get', url: productPath, params })
}

export function getDeviceProduct(productId: string) {
  return requestApi<DeviceProductDetail>({ method: 'get', url: `${productPath}/${encoded(productId)}` })
}

export function createDeviceProduct(data: DeviceProductForm) {
  return requestApi<DeviceProductDetail>({ method: 'post', url: productPath, data })
}

export function updateDeviceProduct(productId: string, data: Omit<DeviceProductForm, 'productCode'>) {
  return requestApi<DeviceProductDetail>({ method: 'put', url: `${productPath}/${encoded(productId)}`, data })
}

export function copyDeviceProduct(productId: string, data: { productCode: string; productName: string }) {
  return requestApi<DeviceProductDetail>({ method: 'post', url: `${productPath}/${encoded(productId)}/copy`, data })
}

export function listPendingDevices(params: { page: number; size: number; status?: string; identity?: string; profileCode?: string }) {
  return requestApi<OnboardingPage<PendingDevice>>({ method: 'get', url: `${onboardingPath}/pending`, params })
}

export function getPendingDevice(pendingId: string) {
  return requestApi<PendingDeviceDetail>({ method: 'get', url: `${onboardingPath}/pending/${encoded(pendingId)}` })
}

export function updatePendingStatus(pendingId: string, data: PendingStatusRequest) {
  return requestApi<PendingDeviceDetail>({ method: 'put', url: `${onboardingPath}/pending/${encoded(pendingId)}/status`, data })
}
