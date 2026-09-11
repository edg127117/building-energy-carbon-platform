<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElPopconfirm,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  Eye,
  RefreshCw,
  Search,
  Settings2,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import { useSensitiveChange } from '@/modules/access-control/public'
import BindingDraftDialog from '../components/BindingDraftDialog.vue'
import PendingStatusTag from '../components/PendingStatusTag.vue'
import { useDeviceOnboarding } from '../composables/use-device-onboarding'
import { canRunOnboardingAction, type PendingBindRequest, type PendingDevice } from '../models/onboarding'

const management = useDeviceOnboarding()
const sensitiveChange = useSensitiveChange()
const detailOpen = ref(false)
const bindingOpen = ref(false)
const selectedPending = computed(() => management.selectedPending.value)
const bindingSubmitting = computed(() => {
  const pendingId = selectedPending.value?.pendingId
  return pendingId ? sensitiveChange.isPending(`start:BIND_PENDING_DEVICE:${pendingId}`) : false
})
const sensitiveError = computed(() => sensitiveChange.error.value)
const latestMetrics = computed(() => {
  const metrics = selectedPending.value?.latestMetrics
  return metrics == null ? t('common.missing') : JSON.stringify(metrics, null, 2)
})

async function query() {
  try {
    await management.setPendingQuery({
      status: management.pendingQuery.value.status,
      identity: management.pendingQuery.value.identity,
      profileCode: management.pendingQuery.value.profileCode,
    })
  } catch {
    // 查询错误已保留在 pendingError，避免事件处理器产生未处理拒绝。
  }
}

async function resetFilters() {
  management.pendingQuery.value = { page: 1, size: management.pendingQuery.value.size, status: undefined, identity: '', profileCode: '' }
  try {
    await management.loadPendingDevices()
  } catch {
    // 查询错误已保留在 pendingError。
  }
}

async function openDetail(pendingId: string) {
  detailOpen.value = true
  try {
    await management.selectPending(pendingId)
  } catch {
    detailOpen.value = false
  }
}

async function ignore(item: PendingDevice) {
  try {
    await management.changePendingStatus(item.pendingId, 'IGNORED')
    if (selectedPending.value?.pendingId === item.pendingId) await management.selectPending(item.pendingId)
    ElMessage.success(t('deviceOnboarding.messages.ignored'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function restore(item: PendingDevice) {
  try {
    await management.changePendingStatus(item.pendingId, 'DISCOVERED')
    if (selectedPending.value?.pendingId === item.pendingId) await management.selectPending(item.pendingId)
    ElMessage.success(t('deviceOnboarding.messages.restored'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function openBinding() {
  try {
    await management.setProductQuery({ page: 1, size: 100, status: 'ENABLED', keyword: '' })
    await management.selectProduct(null)
    bindingOpen.value = true
  } catch {
    // 页面已保留受控错误状态。
  }
}

async function openBindingFor(item: PendingDevice) {
  await openDetail(item.pendingId)
  if (management.selectedPending.value?.pendingId === item.pendingId) await openBinding()
}

async function selectProduct(productId: string) {
  try {
    await management.selectProduct(productId)
  } catch {
    // 产品详情读取失败会保留在产品详情状态，表单不伪造模板。
  }
}

/**
 * 绑定命令由权限模块创建并提交审批申请，旧绑定直写接口不再是本模块依赖。
 */
async function submitBindingDraft(request: PendingBindRequest) {
  const pending = selectedPending.value
  if (!pending) return
  try {
    const change = await sensitiveChange.start(
      'BIND_PENDING_DEVICE',
      { pendingId: pending.pendingId, ...request },
      `BIND_PENDING_DEVICE:${pending.pendingId}`,
    )
    if (!change) return
    bindingOpen.value = false
    ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch {
    // 权限模块已把传输错误转换为可展示的受控错误状态。
  }
}

async function submitIdentityChange(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY') {
  const identityId = selectedPending.value?.boundIdentityId
  if (!identityId) return
  try {
    const change = await sensitiveChange.start(operation, { identityId }, `${operation}:${identityId}`)
    if (change) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch {
    // 权限模块已把传输错误转换为可展示的受控错误状态。
  }
}

function identityChangeSubmitting(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY', identityId: string) {
  return sensitiveChange.isPending(`start:${operation}:${identityId}`)
}

function can(value: unknown, action: string) {
  return canRunOnboardingAction(value as { allowedActions?: string[] }, action)
}

function asPending(value: unknown): PendingDevice { return value as PendingDevice }

onMounted(() => { void management.loadPendingDevices().catch(() => undefined) })
</script>

<template>
  <section class="pending-page">
    <header class="page-heading"><div><h1>{{ t('deviceOnboarding.pending.title') }}</h1><p>{{ t('deviceOnboarding.pending.description') }}</p></div></header>
    <ElAlert v-if="management.pendingError.value" :title="management.pendingError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="sensitiveError" :title="sensitiveError" type="error" show-icon :closable="false" />
    <ElCard shadow="never">
      <div class="filter-bar"><ElSelect v-model="management.pendingQuery.value.status" clearable :placeholder="t('deviceOnboarding.labels.status')"><ElOption value="DISCOVERED" :label="t('deviceOnboarding.status.discovered')" /><ElOption value="IGNORED" :label="t('deviceOnboarding.status.ignored')" /><ElOption value="BOUND" :label="t('deviceOnboarding.status.bound')" /></ElSelect><ElInput v-model="management.pendingQuery.value.identity" :placeholder="t('deviceOnboarding.labels.identity')" clearable @keyup.enter="query"><template #prefix><Search aria-hidden="true" /></template></ElInput><ElInput v-model="management.pendingQuery.value.profileCode" :placeholder="t('deviceOnboarding.labels.expectedProfile')" clearable @keyup.enter="query" /><ElButton :icon="Search" @click="query">{{ t('deviceOnboarding.actions.query') }}</ElButton><ElButton :icon="RefreshCw" @click="resetFilters">{{ t('deviceOnboarding.actions.reset') }}</ElButton></div>
      <ElSkeleton v-if="management.pendingLoading.value && !management.pendingDevices.value.items.length" animated :rows="5" />
      <ElTable v-else :data="management.pendingDevices.value.items" row-key="pendingId">
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="maskedIdentityValue" min-width="200" />
        <ElTableColumn :label="t('deviceOnboarding.labels.expectedProfile')" prop="profileCode" min-width="150" />
        <ElTableColumn :label="t('deviceOnboarding.labels.reportCount')" min-width="110"><template #default="{ row }">{{ formatNumber(row.reportCount) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.lastSeen')" min-width="180"><template #default="{ row }">{{ formatDateTime(row.lastSeenTime) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" min-width="100"><template #default="{ row }"><PendingStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.actions.viewDetail')" min-width="260" fixed="right"><template #default="{ row }"><div class="row-actions"><ElButton :icon="Eye" link @click="openDetail(row.pendingId)">{{ t('deviceOnboarding.actions.viewDetail') }}</ElButton><ElPopconfirm v-if="can(row, 'IGNORE')" :title="t('deviceOnboarding.messages.ignoreConfirm')" :confirm-button-text="t('deviceOnboarding.actions.ignore')" :cancel-button-text="t('deviceOnboarding.actions.cancel')" @confirm="ignore(asPending(row))"><template #reference><ElButton link type="warning">{{ t('deviceOnboarding.actions.ignore') }}</ElButton></template></ElPopconfirm><ElButton v-if="can(row, 'RESTORE')" link @click="restore(asPending(row))">{{ t('deviceOnboarding.actions.restore') }}</ElButton><ElButton v-if="can(row, 'BIND')" :icon="Settings2" link type="primary" @click="openBindingFor(asPending(row))">{{ t('deviceOnboarding.actions.prepareBinding') }}</ElButton></div></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('deviceOnboarding.empty.pending')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="management.pendingDevices.value.page" :page-size="management.pendingDevices.value.size" :total="management.pendingDevices.value.total" @current-change="page => management.setPendingQuery({ page }, false)" /></div>
    </ElCard>

    <ElDrawer :model-value="detailOpen" size="55%" :title="t('deviceOnboarding.pending.detail')" @update:model-value="detailOpen = false">
      <ElSkeleton v-if="management.pendingDetailLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.pendingDetailError.value" :title="management.pendingDetailError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="selectedPending"><div class="drawer-heading"><div><h2>{{ selectedPending.identityValue }}</h2><p>{{ selectedPending.profileCode }}</p></div><PendingStatusTag :status="selectedPending.status" /></div><ElDescriptions :column="2" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.identityType')">{{ selectedPending.identityType }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.profileVersion')">{{ formatNumber(selectedPending.lastProfileVersion) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.reportCount')">{{ formatNumber(selectedPending.reportCount) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.firstSeen')">{{ formatDateTime(selectedPending.firstSeenTime) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.lastSeen')">{{ formatDateTime(selectedPending.lastSeenTime) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.sampleTruncated')">{{ selectedPending.sampleTruncated ? t('deviceOnboarding.labels.enabled') : t('common.missing') }}</ElDescriptionsItem></ElDescriptions><section class="sample-section"><h3>{{ t('deviceOnboarding.pending.latestSample') }}</h3><pre>{{ latestMetrics }}</pre></section><div class="drawer-actions"><ElPopconfirm v-if="can(selectedPending, 'IGNORE')" :title="t('deviceOnboarding.messages.ignoreConfirm')" :confirm-button-text="t('deviceOnboarding.actions.ignore')" :cancel-button-text="t('deviceOnboarding.actions.cancel')" @confirm="ignore(selectedPending)"><template #reference><ElButton type="warning">{{ t('deviceOnboarding.actions.ignore') }}</ElButton></template></ElPopconfirm><ElButton v-if="can(selectedPending, 'RESTORE')" @click="restore(selectedPending)">{{ t('deviceOnboarding.actions.restore') }}</ElButton><ElButton v-if="can(selectedPending, 'BIND')" type="primary" :icon="Settings2" @click="openBinding">{{ t('deviceOnboarding.actions.prepareBinding') }}</ElButton><ElButton v-if="selectedPending.boundIdentityId" type="primary" :loading="identityChangeSubmitting('ACTIVATE_DEVICE_IDENTITY', selectedPending.boundIdentityId)" @click="submitIdentityChange('ACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.activateIdentity') }}</ElButton><ElButton v-if="selectedPending.boundIdentityId" type="warning" :loading="identityChangeSubmitting('DEACTIVATE_DEVICE_IDENTITY', selectedPending.boundIdentityId)" @click="submitIdentityChange('DEACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.deactivateIdentity') }}</ElButton></div></template>
    </ElDrawer>

    <BindingDraftDialog :open="bindingOpen" :pending="selectedPending" :products="management.products.value.items" :product="management.selectedProduct.value" :product-loading="management.productDetailLoading.value" :submitting="bindingSubmitting" @close="bindingOpen = false" @product-change="selectProduct" @submit="submitBindingDraft" />
  </section>
</template>

<style scoped>
.pending-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .drawer-heading, .filter-bar, .row-actions, .drawer-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
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
.drawer-actions { justify-content: flex-end; margin-top: var(--bec-space-section); }
.row-actions { gap: var(--bec-space-tight); flex-wrap: wrap; }
.sample-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
pre { margin: 0; padding: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); overflow: auto; }
</style>
