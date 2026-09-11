import { ref } from 'vue'
import {
  copyDeviceProduct,
  createDeviceProduct,
  getDeviceProduct,
  getPendingDevice,
  listDeviceProducts,
  listPendingDevices,
  updateDeviceProduct,
  updatePendingStatus,
} from '../api/onboarding'
import type {
  DeviceProductDetail,
  DeviceProductForm,
  DeviceProductListItem,
  OnboardingPage,
  PendingDevice,
  PendingDeviceDetail,
  PendingStatus,
} from '../models/onboarding'
import { requestErrorMessage } from '@/shared/utils/request-error'

type RequestState = { message: string } | null
const emptyPage = <T>(size = 20): OnboardingPage<T> => ({ page: 1, size, total: 0, items: [] })

/**
 * 产品草稿和待绑定设备的异步状态边界。
 *
 * 列表与详情用代次隔离迟到响应，草稿保存和待处理状态变更按对象加锁；敏感变更申请由权限模块单独编排。
 */
export function useDeviceOnboarding() {
  const productQuery = ref({ page: 1, size: 20, status: undefined as string | undefined, keyword: '' })
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
  const pendingDetailLoading = ref(false)
  const pendingDetailError = ref<RequestState>(null)
  const running = ref(new Set<string>())
  const operationError = ref<RequestState>(null)

  let productGeneration = 0
  let productDetailGeneration = 0
  let pendingGeneration = 0
  let pendingDetailGeneration = 0

  async function loadProducts() {
    const owner = ++productGeneration
    productsLoading.value = true
    productsError.value = null
    try {
      const page = await listDeviceProducts({
        page: productQuery.value.page,
        size: productQuery.value.size,
        status: productQuery.value.status,
        keyword: trimmed(productQuery.value.keyword),
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

  async function loadPendingDevices() {
    const owner = ++pendingGeneration
    pendingLoading.value = true
    pendingError.value = null
    try {
      const page = await listPendingDevices({
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
      return
    }
    pendingDetailLoading.value = true
    pendingDetailError.value = null
    try {
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
      const detail = await updatePendingStatus(pendingId, { status, reason: trimmed(reason) ?? null })
      selectedPending.value = detail
      await loadPendingDevices()
      return detail
    })
  }

  return {
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
    pendingDetailLoading,
    pendingDetailError,
    running,
    operationError,
    loadProducts,
    setProductQuery,
    selectProduct,
    saveProduct,
    copyProduct,
    loadPendingDevices,
    setPendingQuery,
    selectPending,
    changePendingStatus,
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
  return { message: requestErrorMessage(reason) }
}
