import { ref } from 'vue'
import {
  copyDeviceProduct,
  createDeviceProduct,
  getDeviceProduct,
  getPendingDevice,
  getPendingDeviceConnection,
  getOperationsPendingConnection,
  getOperationsPendingDevice,
  getOperationsCompatibleProduct,
  getOperationsBindingOptions,
  getHvacTemperatureBatchJob,
  getLatestHvacTemperatureBatchJob,
  getHvacTemperatureBindingOptions,
  getDaikinDirectorySync,
  listDaikinSources,
  listDaikinDirectorySyncJobs,
  listDeviceProducts,
  listEquipmentTypes,
  listPendingDevices,
  listOperationsCompatibleProducts,
  listOperationsPendingDevices,
  listOperationsNumericSources,
  listPointNamingRules,
  requestDaikinDirectorySync,
  createHvacTemperatureBatchJob,
  createHvacTemperatureRuleRequest,
  previewHvacTemperatureBindings,
  retryHvacTemperatureBatchJob,
  submitOperationsBinding,
  submitOperationsBindingBatch,
  submitOperationsIdentityStatus,
  updateDeviceProduct,
  updateOperationsPendingStatus,
  updatePendingStatus,
} from '../api/onboarding'
import type {
  EquipmentTypeOption,
  DeviceProductDetail,
  DeviceProductForm,
  DeviceProductListItem,
  OnboardingPage,
  PendingDevice,
  PendingDeviceDetail,
  PendingDeviceConnection,
  PendingStatus,
  PointNamingRule,
  BindingProduct,
  DaikinDirectoryDetail,
  DaikinSyncJob,
  DaikinSourceOption,
  OperationsBindingApplication,
  PendingBindRequest,
  NumericSourceOption,
  OperationsBindingOptions,
  TemperatureBatchJob,
  TemperatureBatchRequestItem,
  TemperatureBindingOptions,
  TemperaturePlanView,
  TemperaturePreviewItem,
  TemperatureRuleRequest,
} from '../models/onboarding'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'

type RequestState = { message: string; code: string | null } | null
const emptyPage = <T>(size = 20): OnboardingPage<T> => ({ page: 1, size, total: 0, items: [] })

/**
 * 产品草稿和待绑定设备的异步状态边界。
 *
 * 列表与详情用代次隔离迟到响应，草稿保存和待处理状态变更按对象加锁；敏感变更申请由权限模块单独编排。
 */
export function useDeviceOnboarding(options: { operations?: boolean } = {}) {
  const operations = options.operations === true
  const equipmentTypes = ref<EquipmentTypeOption[]>([])
  const equipmentTypesError = ref<RequestState>(null)
  const equipmentTypesLoading = ref(false)
  let equipmentTypesGeneration = 0

  async function loadEquipmentTypes() {
    const owner = ++equipmentTypesGeneration
    equipmentTypesLoading.value = true
    equipmentTypesError.value = null
    try {
      const result = await listEquipmentTypes()
      if (owner === equipmentTypesGeneration) equipmentTypes.value = result
    } catch (reason) {
      if (owner === equipmentTypesGeneration) equipmentTypesError.value = requestState(reason)
    } finally {
      if (owner === equipmentTypesGeneration) equipmentTypesLoading.value = false
    }
  }
  const productQuery = ref({
    page: 1,
    size: 20,
    status: undefined as string | undefined,
    keyword: '',
    expectedProfileCode: undefined as string | undefined,
    identityType: undefined as string | undefined,
  })
  const products = ref<OnboardingPage<DeviceProductListItem>>(emptyPage())
  const productsLoading = ref(false)
  const productsError = ref<RequestState>(null)
  const selectedProduct = ref<DeviceProductDetail | null>(null)
  const productDetailLoading = ref(false)
  const productDetailError = ref<RequestState>(null)

  const pendingQuery = ref({ page: 1, size: 20, status: undefined as string | undefined, identity: '', profileCode: '' })
  const pendingDevices = ref<OnboardingPage<PendingDevice>>(emptyPage())
  const pendingLoading = ref(false)
  const pendingError = ref<RequestState>(null)
  const selectedPending = ref<PendingDeviceDetail | null>(null)
  const selectedDirectory = ref<DaikinDirectoryDetail | null>(null)
  const selectedBindingProduct = ref<BindingProduct | null>(null)
  const syncJob = ref<DaikinSyncJob | null>(null)
  const syncJobs = ref<OnboardingPage<DaikinSyncJob>>(emptyPage(10))
  const syncJobsLoading = ref(false)
  const syncJobsError = ref<RequestState>(null)
  const daikinSources = ref<DaikinSourceOption[]>([])
  const daikinSourcesLoading = ref(false)
  const daikinSourcesError = ref<RequestState>(null)
  const bindingApplications = ref<OperationsBindingApplication[]>([])
  const numericSources = ref<NumericSourceOption[]>([])
  const numericSourcesLoading = ref(false)
  const numericSourcesError = ref<RequestState>(null)
  const bindingOptions = ref<OperationsBindingOptions | null>(null)
  const temperatureOptions = ref<TemperatureBindingOptions | null>(null)
  const temperatureOptionsLoading = ref(false)
  const temperatureOptionsError = ref<RequestState>(null)
  const temperaturePlans = ref<TemperaturePlanView[]>([])
  const temperaturePreviewLoading = ref(false)
  const temperaturePreviewError = ref<RequestState>(null)
  const temperatureBatchJob = ref<TemperatureBatchJob | null>(null)
  const temperatureBatchJobLoading = ref(false)
  const temperatureBatchJobError = ref<RequestState>(null)
  const temperatureRuleRequest = ref<{ requestId: string; status: string } | null>(null)
  const temperatureRuleRequestLoading = ref(false)
  const temperatureRuleRequestError = ref<RequestState>(null)
  const pendingDetailLoading = ref(false)
  const pendingDetailError = ref<RequestState>(null)
  const pendingConnection = ref<PendingDeviceConnection | null>(null)
  const pendingConnectionLoading = ref(false)
  const pendingConnectionError = ref<RequestState>(null)
  const namingRules = ref<PointNamingRule[]>([])
  const namingRulesLoading = ref(false)
  const namingRulesError = ref<RequestState>(null)
  const running = ref(new Set<string>())
  const operationError = ref<RequestState>(null)

  let productGeneration = 0
  let productDetailGeneration = 0
  let pendingGeneration = 0
  let pendingDetailGeneration = 0
  let pendingConnectionGeneration = 0
  let namingRulesGeneration = 0
  let syncJobsGeneration = 0
  let daikinSourcesGeneration = 0
  let temperatureOptionsGeneration = 0
  let temperaturePreviewGeneration = 0
  let temperatureBatchJobGeneration = 0

  async function loadProducts() {
    const owner = ++productGeneration
    productsLoading.value = true
    productsError.value = null
    try {
      const pendingId = selectedPending.value?.pendingId
      const page = operations
        ? await listOperationsCompatibleProducts(requiredPendingId(pendingId), {
            page: productQuery.value.page, size: productQuery.value.size,
          })
        : await listDeviceProducts({
            page: productQuery.value.page,
            size: productQuery.value.size,
            status: productQuery.value.status,
            keyword: trimmed(productQuery.value.keyword),
            expectedProfileCode: trimmed(productQuery.value.expectedProfileCode),
            identityType: trimmed(productQuery.value.identityType),
          })
      if (owner === productGeneration) products.value = page
      return page
    } catch (reason) {
      if (owner === productGeneration) productsError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === productGeneration) productsLoading.value = false
    }
  }

  function setProductQuery(patch: Partial<typeof productQuery.value>, resetPage = true) {
    productQuery.value = { ...productQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? productQuery.value.page) }
    return loadProducts()
  }

  async function selectProduct(productId: string | null) {
    const owner = ++productDetailGeneration
    if (!productId) {
      selectedProduct.value = null
      return
    }
    productDetailLoading.value = true
    productDetailError.value = null
    try {
      const detail = await getDeviceProduct(productId)
      if (owner === productDetailGeneration) selectedProduct.value = detail
      return detail
    } catch (reason) {
      if (owner === productDetailGeneration) productDetailError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === productDetailGeneration) productDetailLoading.value = false
    }
  }

  async function selectBindingProduct(productId: string | null) {
    if (!operations) return selectProduct(productId)
    if (!productId) {
      selectedBindingProduct.value = null
      return null
    }
    const pendingId = requiredPendingId(selectedPending.value?.pendingId)
    selectedBindingProduct.value = await getOperationsCompatibleProduct(pendingId, productId)
    return selectedBindingProduct.value
  }

  async function loadNumericSources() {
    if (!operations) return []
    numericSourcesLoading.value = true
    numericSourcesError.value = null
    try {
      numericSources.value = await listOperationsNumericSources(requiredPendingId(selectedPending.value?.pendingId))
      return numericSources.value
    } catch (reason) {
      numericSourcesError.value = requestState(reason)
      throw reason
    } finally {
      numericSourcesLoading.value = false
    }
  }

  async function loadBindingOptions(params: { page?: number; size?: number; spaceId?: string; systemGroupId?: string } = {}) {
    if (!operations) return null
    bindingOptions.value = await getOperationsBindingOptions(requiredPendingId(selectedPending.value?.pendingId), {
      page: params.page ?? 1, size: params.size ?? 20,
      spaceId: params.spaceId, systemGroupId: params.systemGroupId,
      productId: selectedBindingProduct.value?.productId,
    })
    return bindingOptions.value
  }

  async function loadTemperatureOptions(pendingId: string) {
    const owner = ++temperatureOptionsGeneration
    temperatureOptionsLoading.value = true
    temperatureOptionsError.value = null
    temperatureOptions.value = null
    try {
      const result = await getHvacTemperatureBindingOptions(pendingId)
      if (owner === temperatureOptionsGeneration) temperatureOptions.value = result
      return result
    } catch (reason) {
      if (owner === temperatureOptionsGeneration) temperatureOptionsError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === temperatureOptionsGeneration) temperatureOptionsLoading.value = false
    }
  }

  async function previewTemperaturePlans(items: TemperaturePreviewItem[]) {
    const owner = ++temperaturePreviewGeneration
    temperaturePreviewLoading.value = true
    temperaturePreviewError.value = null
    try {
      const result = await previewHvacTemperatureBindings(items)
      if (owner === temperaturePreviewGeneration) temperaturePlans.value = result
      return result
    } catch (reason) {
      if (owner === temperaturePreviewGeneration) temperaturePreviewError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === temperaturePreviewGeneration) temperaturePreviewLoading.value = false
    }
  }

  function clearTemperaturePlans() {
    ++temperaturePreviewGeneration
    temperaturePlans.value = []
    temperaturePreviewError.value = null
    temperaturePreviewLoading.value = false
  }

  function createTemperatureBatchJob(idempotencyKey: string, items: TemperatureBatchRequestItem[]) {
    return run('temperature:batch:create', async () => {
      temperatureBatchJobLoading.value = true
      temperatureBatchJobError.value = null
      try {
        temperatureBatchJob.value = await createHvacTemperatureBatchJob(idempotencyKey, items)
        return temperatureBatchJob.value
      } catch (reason) {
        temperatureBatchJobError.value = requestState(reason)
        throw reason
      } finally {
        temperatureBatchJobLoading.value = false
      }
    })
  }

  async function loadTemperatureBatchJob(jobId: string) {
    const owner = ++temperatureBatchJobGeneration
    temperatureBatchJobLoading.value = true
    temperatureBatchJobError.value = null
    try {
      const result = await getHvacTemperatureBatchJob(jobId)
      if (owner === temperatureBatchJobGeneration) temperatureBatchJob.value = result
      return result
    } catch (reason) {
      if (owner === temperatureBatchJobGeneration) temperatureBatchJobError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === temperatureBatchJobGeneration) temperatureBatchJobLoading.value = false
    }
  }

  async function loadLatestTemperatureBatchJob(pendingId: string) {
    const owner = ++temperatureBatchJobGeneration
    temperatureBatchJobLoading.value = true
    temperatureBatchJobError.value = null
    try {
      const result = await getLatestHvacTemperatureBatchJob(pendingId)
      if (owner === temperatureBatchJobGeneration) temperatureBatchJob.value = result
      return result
    } catch (reason) {
      if (owner === temperatureBatchJobGeneration) temperatureBatchJobError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === temperatureBatchJobGeneration) temperatureBatchJobLoading.value = false
    }
  }

  function clearTemperatureBatchJob() {
    ++temperatureBatchJobGeneration
    temperatureBatchJob.value = null
    temperatureBatchJobError.value = null
    temperatureBatchJobLoading.value = false
  }

  function retryTemperatureBatchJob(jobId: string, pendingIds: string[]) {
    return run(`temperature:batch:retry:${jobId}`, async () => {
      temperatureBatchJobLoading.value = true
      temperatureBatchJobError.value = null
      try {
        temperatureBatchJob.value = await retryHvacTemperatureBatchJob(jobId, pendingIds)
        return temperatureBatchJob.value
      } catch (reason) {
        temperatureBatchJobError.value = requestState(reason)
        throw reason
      } finally {
        temperatureBatchJobLoading.value = false
      }
    })
  }

  async function submitTemperatureRuleRequest(request: TemperatureRuleRequest) {
    temperatureRuleRequestLoading.value = true
    temperatureRuleRequestError.value = null
    try {
      temperatureRuleRequest.value = await createHvacTemperatureRuleRequest(request)
      return temperatureRuleRequest.value
    } catch (reason) {
      temperatureRuleRequestError.value = requestState(reason)
      throw reason
    } finally {
      temperatureRuleRequestLoading.value = false
    }
  }

  async function loadPendingDevices() {
    const owner = ++pendingGeneration
    pendingLoading.value = true
    pendingError.value = null
    try {
      const page = operations
        ? await listOperationsPendingDevices({
            page: pendingQuery.value.page, size: pendingQuery.value.size, status: pendingQuery.value.status,
          })
        : await listPendingDevices({
            page: pendingQuery.value.page,
            size: pendingQuery.value.size,
            status: pendingQuery.value.status,
            identity: trimmed(pendingQuery.value.identity),
            profileCode: trimmed(pendingQuery.value.profileCode),
          })
      if (owner === pendingGeneration) pendingDevices.value = page
      return page
    } catch (reason) {
      if (owner === pendingGeneration) pendingError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === pendingGeneration) pendingLoading.value = false
    }
  }

  function setPendingQuery(patch: Partial<typeof pendingQuery.value>, resetPage = true) {
    pendingQuery.value = { ...pendingQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? pendingQuery.value.page) }
    return loadPendingDevices()
  }

  async function selectPending(pendingId: string | null) {
    const owner = ++pendingDetailGeneration
    if (!pendingId) {
      selectedPending.value = null
      selectedDirectory.value = null
      selectedBindingProduct.value = null
      syncJob.value = null
      clearPendingConnection()
      return
    }
    pendingDetailLoading.value = true
    pendingDetailError.value = null
    try {
      if (operations) {
        const detail = await getOperationsPendingDevice(pendingId)
        if (owner === pendingDetailGeneration) {
          selectedPending.value = detail.pending
          selectedDirectory.value = { ...detail.directory, location: detail.location ?? null }
          selectedBindingProduct.value = null
          syncJob.value = null
        }
        return detail.pending
      }
      const detail = await getPendingDevice(pendingId)
      if (owner === pendingDetailGeneration) selectedPending.value = detail
      return detail
    } catch (reason) {
      if (owner === pendingDetailGeneration) pendingDetailError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === pendingDetailGeneration) pendingDetailLoading.value = false
    }
  }

  async function loadPendingConnection(pendingId: string) {
    const owner = ++pendingConnectionGeneration
    pendingConnectionLoading.value = true
    pendingConnectionError.value = null
    try {
      const response = operations
        ? await getOperationsPendingConnection(pendingId)
        : await getPendingDeviceConnection(pendingId)
      // 运维读数进入暖通监测页；旧资产读数接口仅管理员可用，因此此处不暴露其入口。
      const value = operations ? { ...response, equipmentId: null } : response
      if (owner === pendingConnectionGeneration) pendingConnection.value = value
      return value
    } catch (reason) {
      if (owner === pendingConnectionGeneration) pendingConnectionError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === pendingConnectionGeneration) pendingConnectionLoading.value = false
    }
  }

  function clearPendingConnection() {
    ++pendingConnectionGeneration
    pendingConnection.value = null
    pendingConnectionLoading.value = false
    pendingConnectionError.value = null
  }

  async function loadNamingRules() {
    if (operations) {
      namingRules.value = []
      return namingRules.value
    }
    const owner = ++namingRulesGeneration
    namingRulesLoading.value = true
    namingRulesError.value = null
    try {
      const value = await listPointNamingRules()
      if (owner === namingRulesGeneration) namingRules.value = value
      return value
    } catch (reason) {
      if (owner === namingRulesGeneration) namingRulesError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === namingRulesGeneration) namingRulesLoading.value = false
    }
  }

  function run<T>(key: string, task: () => Promise<T>): Promise<T | undefined> {
    if (running.value.has(key)) return Promise.resolve(undefined)
    running.value = new Set(running.value).add(key)
    operationError.value = null
    return task()
      .catch(reason => {
        operationError.value = requestState(reason)
        throw reason
      })
      .finally(() => {
        const next = new Set(running.value)
        next.delete(key)
        running.value = next
      })
  }

  function saveProduct(productId: string | null, form: DeviceProductForm) {
    return run(productId ? `product:update:${productId}` : 'product:create', async () => {
      const detail = productId
        ? await updateDeviceProduct(productId, productUpdate(form))
        : await createDeviceProduct(form)
      selectedProduct.value = detail
      await loadProducts()
      return detail
    })
  }

  function copyProduct(productId: string, productCode: string, productName: string) {
    return run(`product:copy:${productId}`, async () => {
      const detail = await copyDeviceProduct(productId, { productCode, productName })
      await loadProducts()
      return detail
    })
  }

  function changePendingStatus(pendingId: string, status: PendingStatus, reason?: string) {
    return run(`pending:status:${pendingId}`, async () => {
      const detail = operations
        ? await updateOperationsPendingStatus(pendingId, { status, reason: trimmed(reason) ?? null })
        : await updatePendingStatus(pendingId, { status, reason: trimmed(reason) ?? null })
      selectedPending.value = detail
      await loadPendingDevices()
      return detail
    })
  }

  function submitBindingRequest(pendingId: string, binding: PendingBindRequest, idempotencyKey: string) {
    return run(`pending:binding:${pendingId}`, async () => {
      const application = await submitOperationsBinding(pendingId, binding, idempotencyKey)
      bindingApplications.value = [application]
      await loadPendingDevices()
      return application
    })
  }

  function submitBindingBatch(items: Array<{ pendingId: string; binding: PendingBindRequest }>, idempotencyKeys: Map<string, string>) {
    return run('pending:binding:batch', async () => {
      const applications = await submitOperationsBindingBatch(items.map(item => ({
        ...item, idempotencyKey: requiredIdempotencyKey(idempotencyKeys.get(item.pendingId)),
      })))
      bindingApplications.value = applications
      await loadPendingDevices()
      return applications
    })
  }

  function startDirectorySync(sourceId: string) {
    return run(`daikin:sync:${sourceId}`, async () => {
      syncJob.value = await requestDaikinDirectorySync(sourceId)
      await loadDirectorySyncJobs(1)
      return syncJob.value
    })
  }

  function submitIdentityStatus(pendingId: string, targetStatus: 'ACTIVE' | 'INACTIVE', idempotencyKey: string) {
    return run(`pending:identity:${pendingId}:${targetStatus}`, async () => {
      const application = await submitOperationsIdentityStatus(pendingId, targetStatus, idempotencyKey)
      bindingApplications.value = [application]
      return application
    })
  }

  function refreshDirectorySync() {
    const job = syncJob.value
    if (!job) return Promise.resolve(undefined)
    return run(`daikin:sync:status:${job.jobId}`, async () => {
      syncJob.value = await getDaikinDirectorySync(job.sourceId, job.jobId)
      await loadDirectorySyncJobs(1)
      return syncJob.value
    })
  }

  async function loadDirectorySyncJobs(page = syncJobs.value.page) {
    if (!operations) return syncJobs.value
    const owner = ++syncJobsGeneration
    syncJobsLoading.value = true
    syncJobsError.value = null
    try {
      const result = await listDaikinDirectorySyncJobs({ page, size: syncJobs.value.size })
      if (owner === syncJobsGeneration) {
        syncJobs.value = result
        if (page === 1) syncJob.value = result.items[0] ?? null
      }
      return result
    } catch (reason) {
      if (owner === syncJobsGeneration) syncJobsError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === syncJobsGeneration) syncJobsLoading.value = false
    }
  }

  async function loadDaikinSources() {
    if (!operations) return daikinSources.value
    const owner = ++daikinSourcesGeneration
    daikinSourcesLoading.value = true
    daikinSourcesError.value = null
    try {
      const result = await listDaikinSources()
      if (owner === daikinSourcesGeneration) daikinSources.value = result
      return result
    } catch (reason) {
      if (owner === daikinSourcesGeneration) daikinSourcesError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === daikinSourcesGeneration) daikinSourcesLoading.value = false
    }
  }

  return {
    equipmentTypes, equipmentTypesError, equipmentTypesLoading, loadEquipmentTypes,
    productQuery,
    products,
    productsLoading,
    productsError,
    selectedProduct,
    productDetailLoading,
    productDetailError,
    pendingQuery,
    pendingDevices,
    pendingLoading,
    pendingError,
    selectedPending,
    selectedDirectory,
    selectedBindingProduct,
    syncJob,
    syncJobs,
    syncJobsLoading,
    syncJobsError,
    daikinSources,
    daikinSourcesLoading,
    daikinSourcesError,
    bindingApplications,
    numericSources,
    numericSourcesLoading,
    numericSourcesError,
    bindingOptions,
    temperatureOptions,
    temperatureOptionsLoading,
    temperatureOptionsError,
    temperaturePlans,
    temperaturePreviewLoading,
    temperaturePreviewError,
    temperatureBatchJob,
    temperatureBatchJobLoading,
    temperatureBatchJobError,
    temperatureRuleRequest,
    temperatureRuleRequestLoading,
    temperatureRuleRequestError,
    pendingDetailLoading,
    pendingDetailError,
    pendingConnection,
    pendingConnectionLoading,
    pendingConnectionError,
    namingRules,
    namingRulesLoading,
    namingRulesError,
    running,
    operationError,
    loadProducts,
    setProductQuery,
    selectProduct,
    selectBindingProduct,
    loadNumericSources,
    loadBindingOptions,
    loadTemperatureOptions,
    previewTemperaturePlans,
    clearTemperaturePlans,
    createTemperatureBatchJob,
    loadTemperatureBatchJob,
    loadLatestTemperatureBatchJob,
    clearTemperatureBatchJob,
    retryTemperatureBatchJob,
    submitTemperatureRuleRequest,
    saveProduct,
    copyProduct,
    loadPendingDevices,
    setPendingQuery,
    selectPending,
    loadPendingConnection,
    clearPendingConnection,
    loadNamingRules,
    changePendingStatus,
    submitBindingRequest,
    submitBindingBatch,
    startDirectorySync,
    refreshDirectorySync,
    loadDirectorySyncJobs,
    loadDaikinSources,
    submitIdentityStatus,
  }
}

function productUpdate(form: DeviceProductForm): Omit<DeviceProductForm, 'productCode'> {
  return {
    productName: form.productName,
    manufacturer: form.manufacturer,
    model: form.model,
    equipmentTypeCode: form.equipmentTypeCode,
    expectedProfileCode: form.expectedProfileCode,
    identityType: form.identityType,
    points: form.points,
  }
}

function trimmed(value: string | undefined): string | undefined {
  const result = value?.trim()
  return result || undefined
}

function requestState(reason: unknown): RequestState {
  return { message: requestErrorMessage(reason), code: requestErrorCode(reason) }
}

function requiredPendingId(value: string | undefined): string {
  if (!value) throw new Error('PENDING_DEVICE_REQUIRED')
  return value
}

function requiredIdempotencyKey(value: string | undefined): string {
  if (!value) throw new Error('IDEMPOTENCY_KEY_REQUIRED')
  return value
}
