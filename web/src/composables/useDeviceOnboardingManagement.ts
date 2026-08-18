import { ref } from 'vue'
import {
  activateDeviceIdentity,
  bindPendingDevice,
  copyDeviceProduct,
  createDeviceProduct,
  disableDeviceProduct,
  enableDeviceProduct,
  getDeviceProduct,
  getPendingDevice,
  pageDeviceProducts,
  pagePendingDevices,
  updateDeviceProduct,
  updatePendingDeviceStatus,
} from '@/api/deviceOnboarding'
import { requestErrorMessage } from '@/utils/request'
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

type ErrorState = { message: string; forbidden: boolean }
const emptyPage = <T>(size = 20): PageResponse<T> => ({ page: 1, size, total: 0, items: [] })

/**
 * 产品和待绑定设备的异步所有权边界。列表、详情均用代次隔离迟到响应，命令按对象加锁，
 * 防止旧筛选覆盖新页面或重复触发绑定、启用等有副作用操作。
 */
export function useDeviceOnboardingManagement() {
  const productQuery = ref<DeviceProductQuery>({ page: 1, size: 20 })
  const productPage = ref<PageResponse<DeviceProductListItem>>(emptyPage())
  const productsLoading = ref(false)
  const productsError = ref<ErrorState | null>(null)
  const selectedProduct = ref<DeviceProductDetail | null>(null)
  const productDetailLoading = ref(false)
  const productDetailError = ref<ErrorState | null>(null)

  const pendingQuery = ref<PendingDeviceQuery>({ page: 1, size: 20 })
  const pendingPage = ref<PageResponse<PendingDeviceListItem>>(emptyPage())
  const pendingLoading = ref(false)
  const pendingError = ref<ErrorState | null>(null)
  const selectedPending = ref<PendingDeviceDetail | null>(null)
  const pendingDetailLoading = ref(false)
  const pendingDetailError = ref<ErrorState | null>(null)
  const bindResult = ref<DeviceBindResult | null>(null)
  const activationResult = ref<IdentityStatusResult | null>(null)
  const running = ref(new Set<string>())

  let productListGeneration = 0
  let productDetailGeneration = 0
  let pendingListGeneration = 0
  let pendingDetailGeneration = 0

  async function loadProducts() {
    const owner = ++productListGeneration
    productsLoading.value = true
    productsError.value = null
    try {
      const page = await pageDeviceProducts({ ...productQuery.value })
      if (owner === productListGeneration) productPage.value = page
      return page
    } catch (reason) {
      if (owner === productListGeneration) productsError.value = errorState(reason)
      throw reason
    } finally {
      if (owner === productListGeneration) productsLoading.value = false
    }
  }

  function setProductQuery(patch: Partial<DeviceProductQuery>, resetPage = true) {
    productQuery.value = { ...productQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? productQuery.value.page) }
    return loadProducts()
  }

  async function selectProduct(productId: string | null) {
    const owner = ++productDetailGeneration
    if (!productId) { selectedProduct.value = null; return }
    productDetailLoading.value = true
    productDetailError.value = null
    try {
      const detail = await getDeviceProduct(productId)
      if (owner === productDetailGeneration) selectedProduct.value = detail
      return detail
    } catch (reason) {
      if (owner === productDetailGeneration) productDetailError.value = errorState(reason)
      throw reason
    } finally {
      if (owner === productDetailGeneration) productDetailLoading.value = false
    }
  }

  async function loadPending() {
    const owner = ++pendingListGeneration
    pendingLoading.value = true
    pendingError.value = null
    try {
      const page = await pagePendingDevices({ ...pendingQuery.value })
      if (owner === pendingListGeneration) pendingPage.value = page
      return page
    } catch (reason) {
      if (owner === pendingListGeneration) pendingError.value = errorState(reason)
      throw reason
    } finally {
      if (owner === pendingListGeneration) pendingLoading.value = false
    }
  }

  function setPendingQuery(patch: Partial<PendingDeviceQuery>, resetPage = true) {
    pendingQuery.value = { ...pendingQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? pendingQuery.value.page) }
    return loadPending()
  }

  async function selectPending(pendingId: string | null) {
    const owner = ++pendingDetailGeneration
    bindResult.value = null
    activationResult.value = null
    if (!pendingId) { selectedPending.value = null; return }
    pendingDetailLoading.value = true
    pendingDetailError.value = null
    try {
      const detail = await getPendingDevice(pendingId)
      if (owner === pendingDetailGeneration) selectedPending.value = detail
      return detail
    } catch (reason) {
      if (owner === pendingDetailGeneration) pendingDetailError.value = errorState(reason)
      throw reason
    } finally {
      if (owner === pendingDetailGeneration) pendingDetailLoading.value = false
    }
  }

  function runCommand<T>(key: string, command: () => Promise<T>): Promise<T | undefined> {
    if (running.value.has(key)) return Promise.resolve(undefined)
    running.value = new Set(running.value).add(key)
    return command().finally(() => {
      const next = new Set(running.value)
      next.delete(key)
      running.value = next
    })
  }

  function saveProduct(productId: string | null, form: DeviceProductForm) {
    return runCommand(productId ? `product:update:${productId}` : 'product:create', async () => {
      const detail = productId
        ? await updateDeviceProduct(productId, omitProductCode(form))
        : await createDeviceProduct(form)
      selectedProduct.value = detail
      await loadProducts()
      return detail
    })
  }

  function copyProduct(productId: string, productCode: string, productName: string) {
    return runCommand(`product:copy:${productId}`, async () => {
      const detail = await copyDeviceProduct(productId, { productCode, productName })
      await loadProducts()
      return detail
    })
  }

  function changeProductEnabled(productId: string, enabled: boolean) {
    return runCommand(`product:${enabled ? 'enable' : 'disable'}:${productId}`, async () => {
      const detail = enabled ? await enableDeviceProduct(productId) : await disableDeviceProduct(productId)
      if (selectedProduct.value?.productId === productId) selectedProduct.value = detail
      await loadProducts()
      return detail
    })
  }

  function changePendingStatus(pendingId: string, status: 'DISCOVERED' | 'IGNORED', reason?: string) {
    return runCommand(`pending:status:${pendingId}`, async () => {
      const detail = await updatePendingDeviceStatus(pendingId, { status, reason: reason?.trim() || null })
      selectedPending.value = detail
      await loadPending()
      return detail
    })
  }

  function bind(pendingId: string, request: DeviceBindRequest) {
    return runCommand(`pending:bind:${pendingId}`, async () => {
      const result = await bindPendingDevice(pendingId, request)
      bindResult.value = result
      selectedPending.value = await getPendingDevice(pendingId)
      await loadPending()
      return result
    })
  }

  function activate(identityId: string) {
    return runCommand(`identity:activate:${identityId}`, async () => {
      const result = await activateDeviceIdentity(identityId)
      activationResult.value = result
      return result
    })
  }

  return {
    productQuery, productPage, productsLoading, productsError, selectedProduct, productDetailLoading, productDetailError,
    pendingQuery, pendingPage, pendingLoading, pendingError, selectedPending, pendingDetailLoading, pendingDetailError,
    bindResult, activationResult, running,
    loadProducts, setProductQuery, selectProduct, saveProduct, copyProduct, changeProductEnabled,
    loadPending, setPendingQuery, selectPending, changePendingStatus, bind, activate,
  }
}

function omitProductCode(form: DeviceProductForm): Omit<DeviceProductForm, 'productCode'> {
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

function errorState(reason: unknown): ErrorState {
  const status = (reason as { response?: { status?: unknown } } | null)?.response?.status
  return { message: requestErrorMessage(reason), forbidden: status === 403 }
}
