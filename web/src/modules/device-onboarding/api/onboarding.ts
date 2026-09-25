import { requestApi } from '@/infrastructure/http/public'
import type {
  EquipmentTypeOption,
  DeviceProductDetail,
  DeviceProductForm,
  DeviceProductListItem,
  OnboardingPage,
  PendingDevice,
  PendingDeviceDetail,
  PendingDeviceConnection,
  PointNamingRule,
  PendingStatusRequest,
  PendingBindRequest,
  OperationsBindingApplication,
  OperationsPendingDetail,
  DaikinSyncJob,
  DaikinSourceOption,
  NumericSourceOption,
  OperationsBindingOptions,
  TemperatureBatchJob,
  TemperatureBatchRequestItem,
  TemperatureBindingOptions,
  TemperaturePlanView,
  TemperaturePreviewItem,
  TemperatureRuleRequest,
} from '../models/onboarding'

const productPath = '/v1/device-products'
const onboardingPath = '/v1/device-onboarding'
const operationsPath = '/v1/operations/device-onboarding'
const temperatureBindingPath = '/v1/operations/hvac-temperature-bindings'
const encoded = (value: string) => encodeURIComponent(value)

export function listEquipmentTypes() {
  return requestApi<EquipmentTypeOption[]>({ method: 'get', url: `${productPath}/equipment-types` })
}

export function listDeviceProducts(params: { page: number; size: number; status?: string; keyword?: string; expectedProfileCode?: string; identityType?: string }) {
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

export function getPendingDeviceConnection(pendingId: string) {
  return requestApi<PendingDeviceConnection>({ method: 'get', url: `${onboardingPath}/pending/${encoded(pendingId)}/connection` })
}

export function listPointNamingRules() {
  return requestApi<PointNamingRule[]>({ method: 'get', url: `${onboardingPath}/naming-rules` })
}

export function updatePendingStatus(pendingId: string, data: PendingStatusRequest) {
  return requestApi<PendingDeviceDetail>({ method: 'put', url: `${onboardingPath}/pending/${encoded(pendingId)}/status`, data })
}

export function listOperationsPendingDevices(params: { page: number; size: number; status?: string }) {
  return requestApi<OnboardingPage<PendingDevice>>({ method: 'get', url: `${operationsPath}/pending`, params })
}

export function getOperationsPendingDevice(pendingId: string) {
  return requestApi<OperationsPendingDetail>({ method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}` })
}

export function getOperationsPendingConnection(pendingId: string) {
  return requestApi<PendingDeviceConnection>({ method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}/connection` })
}

export function listOperationsCompatibleProducts(pendingId: string, params: { page: number; size: number }) {
  return requestApi<OnboardingPage<DeviceProductListItem>>({
    method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}/products`, params,
  })
}

export function getOperationsCompatibleProduct(pendingId: string, productId: string) {
  return requestApi<DeviceProductDetail>({
    method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}/products/${encoded(productId)}`,
  })
}

export function listOperationsNumericSources(pendingId: string) {
  return requestApi<NumericSourceOption[]>({
    method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}/numeric-sources`,
  })
}

export function getOperationsBindingOptions(pendingId: string, params: { page: number; size: number; spaceId?: string; systemGroupId?: string; productId?: string }) {
  return requestApi<OperationsBindingOptions>({
    method: 'get', url: `${operationsPath}/pending/${encoded(pendingId)}/binding-options`, params,
  })
}

export function updateOperationsPendingStatus(pendingId: string, data: PendingStatusRequest) {
  return requestApi<PendingDeviceDetail>({ method: 'put', url: `${operationsPath}/pending/${encoded(pendingId)}/status`, data })
}

export function submitOperationsBinding(pendingId: string, binding: PendingBindRequest, idempotencyKey: string) {
  return requestApi<OperationsBindingApplication>({
    method: 'post', url: `${operationsPath}/pending/${encoded(pendingId)}/binding-requests`,
    data: { binding, idempotencyKey },
  })
}

export function submitOperationsBindingBatch(items: Array<{ pendingId: string; binding: PendingBindRequest; idempotencyKey: string }>) {
  return requestApi<OperationsBindingApplication[]>({
    method: 'post', url: `${operationsPath}/binding-requests/batch`, data: { items },
  })
}

export function submitOperationsIdentityStatus(pendingId: string, targetStatus: 'ACTIVE' | 'INACTIVE', idempotencyKey: string) {
  return requestApi<OperationsBindingApplication>({
    method: 'post', url: `${operationsPath}/pending/${encoded(pendingId)}/identity-status-requests`,
    data: { targetStatus, idempotencyKey },
  })
}

/** 温度模板的匹配、计划摘要和批量任务均由运维端服务端裁决，浏览器只展示并回传已冻结的结果。 */
export function getHvacTemperatureBindingOptions(pendingId: string) {
  return requestApi<TemperatureBindingOptions>({
    method: 'get', url: `${temperatureBindingPath}/pending/${encoded(pendingId)}/options`,
  })
}

export function previewHvacTemperatureBindings(items: TemperaturePreviewItem[]) {
  return requestApi<TemperaturePlanView[]>({ method: 'post', url: `${temperatureBindingPath}/preview`, data: { items } })
}

export function createHvacTemperatureBatchJob(idempotencyKey: string, items: TemperatureBatchRequestItem[]) {
  return requestApi<TemperatureBatchJob>({
    method: 'post', url: `${temperatureBindingPath}/batch-jobs`, data: { idempotencyKey, items } })
}

export function getHvacTemperatureBatchJob(jobId: string) {
  return requestApi<TemperatureBatchJob>({ method: 'get', url: `${temperatureBindingPath}/batch-jobs/${encoded(jobId)}` })
}

export function getLatestHvacTemperatureBatchJob(pendingId: string) {
  return requestApi<TemperatureBatchJob | null>({ method: 'get', url: `${temperatureBindingPath}/batch-jobs/latest`, params: { pendingId } })
}

export function retryHvacTemperatureBatchJob(jobId: string, pendingIds: string[]) {
  return requestApi<TemperatureBatchJob>({
    method: 'post', url: `${temperatureBindingPath}/batch-jobs/${encoded(jobId)}/retry`, data: { pendingIds } })
}

export function createHvacTemperatureRuleRequest(request: TemperatureRuleRequest) {
  return requestApi<{ requestId: string; status: string }>({
    method: 'post', url: `${temperatureBindingPath}/rule-requests`, data: request,
  })
}

export function requestDaikinDirectorySync(sourceId: string) {
  return requestApi<DaikinSyncJob>({ method: 'post', url: `/v1/daikin/sources/${encoded(sourceId)}/sync-jobs` })
}

export function listDaikinSources() {
  return requestApi<DaikinSourceOption[]>({ method: 'get', url: '/v1/daikin/sources' })
}

export function getDaikinDirectorySync(sourceId: string, jobId: string) {
  return requestApi<DaikinSyncJob>({ method: 'get', url: `/v1/daikin/sources/${encoded(sourceId)}/sync-jobs/${encoded(jobId)}` })
}

export function listDaikinDirectorySyncJobs(params: { page: number; size: number }) {
  return requestApi<OnboardingPage<DaikinSyncJob>>({ method: 'get', url: '/v1/daikin/sync-jobs', params })
}
