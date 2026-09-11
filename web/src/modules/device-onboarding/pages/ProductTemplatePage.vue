<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
  Pencil,
  Plus,
  RefreshCw,
  Search,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import { useSensitiveChange } from '@/modules/access-control/public'
import ProductEditorDialog from '../components/ProductEditorDialog.vue'
import ProductStatusTag from '../components/ProductStatusTag.vue'
import { useDeviceOnboarding } from '../composables/use-device-onboarding'
import { canRunOnboardingAction, type DeviceProductDetail } from '../models/onboarding'

const management = useDeviceOnboarding()
const sensitiveChange = useSensitiveChange()
const detailOpen = ref(false)
const editorOpen = ref(false)
const copyOpen = ref(false)
const editingProduct = ref<DeviceProductDetail | null>(null)
const copyCode = ref('')
const copyName = ref('')
const copyError = ref<string | null>(null)
const selectedProduct = computed(() => management.selectedProduct.value)
const editorSubmitting = computed(() => editingProduct.value ? management.running.value.has(`product:update:${editingProduct.value.productId}`) : management.running.value.has('product:create'))
const copySubmitting = computed(() => selectedProduct.value ? management.running.value.has(`product:copy:${selectedProduct.value.productId}`) : false)
const sensitiveError = computed(() => sensitiveChange.error.value)

async function query() {
  try {
    await management.setProductQuery({ status: management.productQuery.value.status, keyword: management.productQuery.value.keyword })
  } catch {
    // 查询错误已保留在 productsError，避免事件处理器产生未处理拒绝。
  }
}

async function resetFilters() {
  management.productQuery.value = { page: 1, size: management.productQuery.value.size, status: undefined, keyword: '' }
  try {
    await management.loadProducts()
  } catch {
    // 查询错误已保留在 productsError。
  }
}

async function openDetail(productId: string) {
  detailOpen.value = true
  try {
    await management.selectProduct(productId)
  } catch {
    detailOpen.value = false
  }
}

function openCreate() {
  editingProduct.value = null
  editorOpen.value = true
}

function openEdit(product: DeviceProductDetail) {
  editingProduct.value = product
  editorOpen.value = true
}

async function save(value: Parameters<typeof management.saveProduct>[1]) {
  try {
    await management.saveProduct(editingProduct.value?.productId ?? null, value)
    editorOpen.value = false
    ElMessage.success(t('deviceOnboarding.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

function openCopy() {
  copyCode.value = ''
  copyName.value = ''
  copyError.value = null
  copyOpen.value = true
}

async function copy() {
  const product = selectedProduct.value
  if (!product || !copyCode.value.trim() || !copyName.value.trim()) {
    copyError.value = t('deviceOnboarding.validation.productCode')
    return
  }
  try {
    await management.copyProduct(product.productId, copyCode.value.trim(), copyName.value.trim())
    copyOpen.value = false
    ElMessage.success(t('deviceOnboarding.messages.copied'))
  } catch {
    // 页面保留服务端错误状态，避免将异常原文透传给用户。
  }
}

/**
 * 产品启停只创建并提交审批申请；页面不会调用已退役的直写状态接口。
 */
async function submitProductChange(operation: 'ENABLE_DEVICE_PRODUCT' | 'DISABLE_DEVICE_PRODUCT') {
  const product = selectedProduct.value
  if (!product) return
  try {
    const change = await sensitiveChange.start(operation, { productId: product.productId }, `${operation}:${product.productId}`)
    if (change) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch {
    // 权限模块已把传输错误转换为可展示的受控错误状态。
  }
}

function productChangeSubmitting(operation: 'ENABLE_DEVICE_PRODUCT' | 'DISABLE_DEVICE_PRODUCT', productId: string) {
  return sensitiveChange.isPending(`start:${operation}:${productId}`)
}

function can(value: unknown, action: string) {
  return canRunOnboardingAction(value as { allowedActions?: string[] }, action)
}

onMounted(() => { void management.loadProducts().catch(() => undefined) })
</script>

<template>
  <section class="product-page">
    <header class="page-heading"><div><h1>{{ t('deviceOnboarding.products.title') }}</h1><p>{{ t('deviceOnboarding.products.description') }}</p></div><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('deviceOnboarding.actions.createProduct') }}</ElButton></header>
    <ElAlert v-if="management.productsError.value" :title="management.productsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="sensitiveError" :title="sensitiveError" type="error" show-icon :closable="false" />
    <ElCard shadow="never">
      <div class="filter-bar"><ElSelect v-model="management.productQuery.value.status" clearable :placeholder="t('deviceOnboarding.labels.status')"><ElOption value="DRAFT" :label="t('deviceOnboarding.status.draft')" /><ElOption value="ENABLED" :label="t('deviceOnboarding.status.enabled')" /><ElOption value="DISABLED" :label="t('deviceOnboarding.status.disabled')" /></ElSelect><ElInput v-model="management.productQuery.value.keyword" :placeholder="t('deviceOnboarding.labels.keyword')" clearable @keyup.enter="query"><template #prefix><Search aria-hidden="true" /></template></ElInput><ElButton :icon="Search" @click="query">{{ t('deviceOnboarding.actions.query') }}</ElButton><ElButton :icon="RefreshCw" @click="resetFilters">{{ t('deviceOnboarding.actions.reset') }}</ElButton></div>
      <ElSkeleton v-if="management.productsLoading.value && !management.products.value.items.length" animated :rows="5" />
      <ElTable v-else :data="management.products.value.items" row-key="productId">
        <ElTableColumn :label="t('deviceOnboarding.labels.productName')" min-width="180"><template #default="{ row }"><ElButton link @click="openDetail(row.productId)">{{ row.productName }}</ElButton></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.productCode')" prop="productCode" min-width="140" />
        <ElTableColumn :label="t('deviceOnboarding.labels.equipmentType')" prop="equipmentTypeCode" min-width="120" />
        <ElTableColumn :label="t('deviceOnboarding.labels.pointCount')" prop="pointCount" min-width="110" />
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" min-width="100"><template #default="{ row }"><ProductStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.updateTime')" min-width="180"><template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.actions.viewDetail')" min-width="120" fixed="right"><template #default="{ row }"><ElButton link @click="openDetail(row.productId)">{{ t('deviceOnboarding.actions.viewDetail') }}</ElButton></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('deviceOnboarding.empty.products')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="management.products.value.page" :page-size="management.products.value.size" :total="management.products.value.total" @current-change="page => management.setProductQuery({ page }, false)" /></div>
    </ElCard>

    <ElDrawer :model-value="detailOpen" size="55%" :title="t('deviceOnboarding.products.detail')" @update:model-value="detailOpen = false">
      <ElSkeleton v-if="management.productDetailLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.productDetailError.value" :title="management.productDetailError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="selectedProduct">
        <div class="drawer-heading"><div><h2>{{ selectedProduct.productName }}</h2><p>{{ selectedProduct.productCode }}</p></div><ProductStatusTag :status="selectedProduct.status" /></div><ElDescriptions :column="2" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.manufacturer')">{{ selectedProduct.manufacturer || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.model')">{{ selectedProduct.model || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.equipmentType')">{{ selectedProduct.equipmentTypeCode }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.expectedProfile')">{{ selectedProduct.expectedProfileCode }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.identityType')">{{ selectedProduct.identityType }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.updateTime')">{{ formatDateTime(selectedProduct.updateTime) }}</ElDescriptionsItem></ElDescriptions>
        <section class="drawer-section"><h3>{{ t('deviceOnboarding.products.points') }}</h3><ElTable :data="selectedProduct.points" row-key="templatePointId"><ElTableColumn :label="t('deviceOnboarding.labels.metricCode')" prop="metricCode" min-width="150" /><ElTableColumn :label="t('deviceOnboarding.labels.pointNameTemplate')" prop="pointNameTemplate" min-width="170" /><ElTableColumn :label="t('deviceOnboarding.labels.unit')" prop="unit" min-width="90" /><ElTableColumn :label="t('deviceOnboarding.labels.minValue')" min-width="100"><template #default="{ row }">{{ formatNumber(row.minValue) }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.maxValue')" min-width="100"><template #default="{ row }">{{ formatNumber(row.maxValue) }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.required')" min-width="100"><template #default="{ row }"><ElTag :type="row.required ? 'success' : 'info'">{{ row.required ? t('deviceOnboarding.labels.required') : t('common.missing') }}</ElTag></template></ElTableColumn><template #empty><ElEmpty :description="t('deviceOnboarding.empty.points')" /></template></ElTable></section>
        <div class="drawer-actions"><ElButton v-if="can(selectedProduct, 'COPY')" @click="openCopy">{{ t('deviceOnboarding.actions.copyProduct') }}</ElButton><ElButton v-if="can(selectedProduct, 'UPDATE')" :icon="Pencil" @click="openEdit(selectedProduct)">{{ t('deviceOnboarding.actions.editProduct') }}</ElButton><ElButton v-if="can(selectedProduct, 'ENABLE')" type="primary" :loading="productChangeSubmitting('ENABLE_DEVICE_PRODUCT', selectedProduct.productId)" @click="submitProductChange('ENABLE_DEVICE_PRODUCT')">{{ t('deviceOnboarding.actions.enableProduct') }}</ElButton><ElButton v-if="can(selectedProduct, 'DISABLE')" type="warning" :loading="productChangeSubmitting('DISABLE_DEVICE_PRODUCT', selectedProduct.productId)" @click="submitProductChange('DISABLE_DEVICE_PRODUCT')">{{ t('deviceOnboarding.actions.disableProduct') }}</ElButton></div>
      </template>
    </ElDrawer>

    <ProductEditorDialog :open="editorOpen" :product="editingProduct" :submitting="editorSubmitting" @close="editorOpen = false" @save="save" />
    <ElDialog :model-value="copyOpen" :title="t('deviceOnboarding.forms.copyProduct')" @update:model-value="copyOpen = false"><ElForm label-position="top"><ElFormItem :label="t('deviceOnboarding.labels.productCode')" required><ElInput v-model="copyCode" maxlength="50" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.productName')" required><ElInput v-model="copyName" maxlength="100" /></ElFormItem><ElAlert v-if="copyError" :title="copyError" type="error" show-icon :closable="false" /></ElForm><template #footer><ElButton @click="copyOpen = false">{{ t('deviceOnboarding.actions.cancel') }}</ElButton><ElButton type="primary" :loading="copySubmitting" @click="copy">{{ t('deviceOnboarding.actions.copyProduct') }}</ElButton></template></ElDialog>
  </section>
</template>

<style scoped>
.product-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .drawer-heading, .filter-bar, .drawer-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .drawer-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.filter-bar { align-items: stretch; }
.filter-bar :deep(.el-input) { flex: 1; }
.filter-bar :deep(.el-select) { min-width: var(--bec-navigation-width); }
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.drawer-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.drawer-actions { justify-content: flex-end; margin-top: var(--bec-space-section); }
</style>
