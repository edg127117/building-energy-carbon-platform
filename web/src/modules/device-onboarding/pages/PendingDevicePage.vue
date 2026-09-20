<script setup lang="ts">
import { computed, onMounted, ref, toRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  CopyableValue,
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
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
  ElTag,
  ElTabPane,
  ElTabs,
  Eye,
  RefreshCw,
  Search,
  Settings2,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { identityTypeText, profileText, readingReasonText, readingStatusText, syncJobResultText, syncJobStatusText } from '../models/reading-labels'
import { ChangeRequestControl, useSensitiveChange } from '@/modules/access-control/public'
import { useEquipmentReadings } from '@/modules/asset-management/public'
import { useSession } from '@/modules/auth/public'
import BindingDraftDialog from '../components/BindingDraftDialog.vue'
import PendingStatusTag from '../components/PendingStatusTag.vue'
import { useDeviceOnboarding } from '../composables/use-device-onboarding'
import { canRunOnboardingAction, type OperationsBindingApplication, type PendingBindRequest, type PendingDevice } from '../models/onboarding'

const session = useSession()
const route = useRoute()
const router = useRouter()
const protocolScope = ref(typeof route.query.profileCode === 'string' ? route.query.profileCode : '')
function returnToDraft() { void router.push({ path: '/configuration/ingestion/protocols', query: { draftId: typeof route.query.draftId === 'string' ? route.query.draftId : undefined } }) }
function clearProtocolScope() { protocolScope.value = ''; management.pendingQuery.value.profileCode = ''; void query() }
const administrator = computed(() => session.user?.roles.includes('PLATFORM_ADMIN') === true)
const operationsMode = ref(!administrator.value || route.query.view !== 'general')
const activeDeviceView = computed(() => operationsMode.value ? 'daikin' : 'general')
const generalManagement = useDeviceOnboarding()
const operationsManagement = useDeviceOnboarding({ operations: true })
// 管理员切换视图时保留两套独立请求状态，避免厂家范围结果和旧全量结果相互覆盖。
const management = new Proxy(generalManagement, {
  get(_target, key: keyof typeof generalManagement) {
    return Reflect.get(operationsMode.value ? operationsManagement : generalManagement, key)
  },
})
const viewSwitchDisabled = computed(() => [generalManagement, operationsManagement].some(state =>
  state.pendingLoading.value || state.pendingDetailLoading.value || state.productsLoading.value
  || state.productDetailLoading.value || state.running.value.size > 0))
const sensitiveChange = useSensitiveChange()
const equipmentReadings = useEquipmentReadings()
const detailOpen = ref(false)
const bindingOpen = ref(false)
const readingsOpen = ref(false)
const bindingContractError = ref<string | null>(null)
const bindingSubmitError = ref<string | null>(null)
const batchPendingIds = ref<string[]>([])
const bindingKeys = new Map<string, string>()
const identityKeys = new Map<string, string>()
const batchResultsOpen = ref(false)
const syncSourceId = ref('')
const syncHistoryOpen = ref(false)
const selectedPending = computed(() => management.selectedPending.value)
const bindingProduct = computed(() => operationsMode.value ? management.selectedBindingProduct.value : management.selectedProduct.value)
const bindingSubmitting = computed(() => {
  const pendingId = selectedPending.value?.pendingId
  if (operationsMode.value) return management.running.value.has(batchPendingIds.value.length > 1 ? 'pending:binding:batch' : `pending:binding:${pendingId}`)
  return pendingId ? sensitiveChange.isPending(`start:BIND_PENDING_DEVICE:${pendingId}`) : false
})
const sensitiveError = computed(() => sensitiveChange.error.value)
const operationMessage = computed(() => {
  const error = management.operationError.value
  if (error?.code === 'DAIKIN_SYNC_PROVIDER_UNAVAILABLE') return t('deviceOnboarding.messages.syncProviderUnavailable')
  if (error?.code === 'DAIKIN_SYNC_DISABLED') return t('deviceOnboarding.messages.syncDisabled')
  return error?.message
})
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
  management.pendingQuery.value = { page: 1, size: management.pendingQuery.value.size, status: undefined, identity: '', profileCode: protocolScope.value }
  try {
    await management.loadPendingDevices()
  } catch {
    // 查询错误已保留在 pendingError。
  }
}

async function openDetail(pendingId: string) {
  detailOpen.value = true
  try {
    await Promise.all([
      management.selectPending(pendingId),
      management.loadPendingConnection(pendingId).catch(() => undefined),
    ])
  } catch {
    detailOpen.value = false
  }
}

async function ignore(item: Pick<PendingDevice, 'pendingId'>) {
  try {
    await management.changePendingStatus(item.pendingId, 'IGNORED')
    if (selectedPending.value?.pendingId === item.pendingId) await management.selectPending(item.pendingId)
    ElMessage.success(t('deviceOnboarding.messages.ignored'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function restore(item: Pick<PendingDevice, 'pendingId'>) {
  try {
    await management.changePendingStatus(item.pendingId, 'DISCOVERED')
    if (selectedPending.value?.pendingId === item.pendingId) await management.selectPending(item.pendingId)
    ElMessage.success(t('deviceOnboarding.messages.restored'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function openBinding() {
  bindingSubmitError.value = null
  const pending = selectedPending.value
  if (!pending) return
  batchPendingIds.value = [pending.pendingId]
  ensureBindingKeys(batchPendingIds.value)
  try {
    bindingContractError.value = null
    await loadBindingProducts({
      page: 1,
      size: 20,
      status: 'ENABLED',
      keyword: '',
      expectedProfileCode: pending.profileCode,
      identityType: pending.identityType,
    })
    if (operationsMode.value) await Promise.all([management.loadNumericSources(), management.loadBindingOptions()])
    else await management.loadNamingRules()
    bindingOpen.value = true
  } catch {
    bindingSubmitError.value = t('deviceOnboarding.messages.bindingPreparationFailed')
  }
}

async function loadBindingProducts(patch: Partial<typeof management.productQuery.value>, resetPage = true) {
  await (operationsMode.value ? management.selectBindingProduct(null) : management.selectProduct(null))
  const result = await management.setProductQuery(patch, resetPage)
  if (toRaw(management.products.value) === result && result.total === 1 && result.items[0]) {
    await selectProduct(result.items[0].productId)
  }
}

async function searchBindingProducts(keyword: string) {
  if (operationsMode.value) return
  try {
    await loadBindingProducts({ keyword })
  } catch {
    // 产品筛选错误已保留在 composable，已有选择不会被迟到响应覆盖。
  }
}

async function changeBindingProductPage(page: number) {
  try {
    await loadBindingProducts({ page }, false)
  } catch {
    // 分页错误已保留在 composable。
  }
}

async function loadBindingEquipment(page: number, spaceId?: string, systemGroupId?: string) {
  try {
    await management.loadBindingOptions({ page, size: 20, spaceId, systemGroupId })
  } catch {
    // 运维绑定候选始终由后端按待接入项目范围和所选空间分页。
  }
}

async function openBindingFor(item: PendingDevice) {
  await openDetail(item.pendingId)
  if (management.selectedPending.value?.pendingId === item.pendingId) await openBinding()
}

async function selectProduct(productId: string) {
  try {
    bindingContractError.value = null
    const detail = await (operationsMode.value ? management.selectBindingProduct(productId) : management.selectProduct(productId))
    const pending = selectedPending.value
    if (detail && pending && (detail.status !== 'ENABLED' || detail.expectedProfileCode !== pending.profileCode || detail.identityType !== pending.identityType)) {
      await management.selectProduct(null)
      bindingContractError.value = t('deviceOnboarding.validation.bindingProductContract')
    }
  } catch {
    // 产品详情读取失败会保留在产品详情状态，表单不伪造模板。
  }
}

/**
 * 绑定命令由权限模块创建并提交审批申请，旧绑定直写接口不再是本模块依赖。
 */
async function submitBindingDraft(request: PendingBindRequest) {
  bindingSubmitError.value = null
  const pending = selectedPending.value
  if (!pending) return
  try {
    if (operationsMode.value) {
      const ids = batchPendingIds.value.length ? batchPendingIds.value : [pending.pendingId]
      ensureBindingKeys(ids)
      const result = ids.length > 1
        ? await management.submitBindingBatch(ids, request, bindingKeys)
        : await management.submitBindingRequest(ids[0]!, request, bindingKeys.get(ids[0]!)!)
      if (!result) return
      bindingOpen.value = false
      batchResultsOpen.value = true
      return
    }
    const change = await sensitiveChange.start(
      'BIND_PENDING_DEVICE',
      {
        pendingId: pending.pendingId,
        productId: request.productId,
        buildingId: request.buildingId,
        spaceId: request.spaceId,
        systemGroupId: request.systemGroupId,
        existingEquipmentId: request.existingEquipmentId,
        newEquipment: request.newEquipment,
        pointBindings: request.pointBindings,
      },
      `BIND_PENDING_DEVICE:${pending.pendingId}`,
    )
    if (!change) return
    bindingOpen.value = false
    ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch (reason) {
    const code = requestErrorCode(reason)
    bindingSubmitError.value = code === 'ONBOARDING_SOURCE_REQUIRED'
      ? t('deviceOnboarding.messages.sourceRequired')
      : code === 'ONBOARDING_SOURCE_AMBIGUOUS'
        ? t('deviceOnboarding.messages.sourceAmbiguous') : requestErrorMessage(reason)
  }
}

async function openBatchBinding() {
  const first = batchPendingIds.value[0]
  if (!first) return
  try {
    await management.selectPending(first)
    ensureBindingKeys(batchPendingIds.value)
    await openBinding()
    batchPendingIds.value = selectedRows.value.map(item => item.pendingId)
    ensureBindingKeys(batchPendingIds.value)
  } catch {
    // 范围或兼容产品错误由页面状态展示；不会跳过后端逐台校验。
  }
}

const selectedRows = ref<PendingDevice[]>([])
function changeSelection(rows: PendingDevice[]) {
  selectedRows.value = rows
  batchPendingIds.value = rows.map(item => item.pendingId)
}

function ensureBindingKeys(ids: string[]) {
  for (const id of ids) if (!bindingKeys.has(id)) bindingKeys.set(id, crypto.randomUUID())
}

async function requestSync() {
  const sourceId = syncSourceId.value
  if (!sourceId) {
    ElMessage.warning(t('deviceOnboarding.messages.syncSourceRequired'))
    return
  }
  try {
    await management.startDirectorySync(sourceId)
  } catch {
    // 后端明确返回权限、配置或任务失败；页面不回退到模拟目录。
  }
}

async function requestDirectorySync() {
  const sourceId = management.selectedDirectory.value?.sourceId
  if (!sourceId) return
  try {
    await management.startDirectorySync(sourceId)
  } catch {
    // 后端明确返回权限、配置或任务失败；页面不回退到模拟目录。
  }
}

async function refreshSync() {
  try {
    await management.refreshDirectorySync()
  } catch {
    // 同步结果查询失败保留在统一操作错误状态。
  }
}

async function openSyncHistory() {
  syncHistoryOpen.value = true
  try {
    await operationsManagement.loadDirectorySyncJobs(1)
  } catch {
    // 授权分页错误由抽屉内受控状态展示。
  }
}

async function changeSyncHistoryPage(page: number) {
  try {
    await operationsManagement.loadDirectorySyncJobs(page)
  } catch {
    // 保留上一页内容并展示查询错误。
  }
}

function asBindingApplication(value: unknown): OperationsBindingApplication {
  return value as OperationsBindingApplication
}

async function switchDeviceView(nextOperationsMode: boolean) {
  if (!administrator.value || viewSwitchDisabled.value || operationsMode.value === nextOperationsMode) return
  operationsMode.value = nextOperationsMode
  detailOpen.value = false
  bindingOpen.value = false
  selectedRows.value = []
  batchPendingIds.value = []
  await router.replace({ query: { ...route.query, view: nextOperationsMode ? undefined : 'general' } })
  await management.loadPendingDevices().catch(() => undefined)
  if (nextOperationsMode) await operationsManagement.loadDirectorySyncJobs(1).catch(() => undefined)
}

function changeDeviceView(value: string | number) {
  void switchDeviceView(value === 'daikin')
}

async function submitIdentityChange(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY') {
  const identityId = management.pendingConnection.value?.identityId
  const pendingId = selectedPending.value?.pendingId
  if (!identityId || !pendingId) return
  try {
    if (operationsMode.value) {
      const targetStatus = operation === 'ACTIVATE_DEVICE_IDENTITY' ? 'ACTIVE' : 'INACTIVE'
      const key = `${pendingId}:${targetStatus}`
      if (!identityKeys.has(key)) identityKeys.set(key, crypto.randomUUID())
      const application = await management.submitIdentityStatus(pendingId, targetStatus, identityKeys.get(key)!)
      if (application) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
      return
    }
    const change = await sensitiveChange.start(operation, { identityId }, `${operation}:${identityId}`)
    if (change) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch {
    // 权限模块已把传输错误转换为可展示的受控错误状态。
  }
}

async function afterApprovalAction(action: () => Promise<unknown>, execute = false) {
  try {
    await action()
    if (execute && selectedPending.value) {
      await Promise.all([
        management.loadPendingDevices(),
        management.selectPending(selectedPending.value.pendingId),
        management.loadPendingConnection(selectedPending.value.pendingId),
      ])
    }
  } catch {
    // 公共审批能力保留标准错误；执行成功后才刷新接入详情与连接状态。
  }
}

async function openReadings() {
  const equipmentId = management.pendingConnection.value?.equipmentId
  if (!equipmentId) return
  readingsOpen.value = true
  try {
    await equipmentReadings.load(equipmentId)
  } catch {
    // 资产模块统一转换读数查询错误。
  }
}

function identityChangeSubmitting(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY', identityId: string) {
  if (operationsMode.value && selectedPending.value) {
    const target = operation === 'ACTIVATE_DEVICE_IDENTITY' ? 'ACTIVE' : 'INACTIVE'
    return management.running.value.has(`pending:identity:${selectedPending.value.pendingId}:${target}`)
  }
  return sensitiveChange.isPending(`start:${operation}:${identityId}`)
}

function can(value: unknown, action: string) {
  return canRunOnboardingAction(value as { allowedActions?: string[] }, action)
}

function asPending(value: unknown): PendingDevice { return value as PendingDevice }

onMounted(() => {
  const profileCode = typeof route.query.profileCode === 'string' ? route.query.profileCode : ''
  management.pendingQuery.value.profileCode = profileCode
  void management.loadPendingDevices().catch(() => undefined)
  if (operationsMode.value) {
    void Promise.all([
      operationsManagement.loadDirectorySyncJobs(1),
      operationsManagement.loadDaikinSources().then(sources => {
        if (sources.length === 1) syncSourceId.value = sources[0]!.sourceId
      }),
    ]).catch(() => undefined)
  }
})
</script>

<template>
  <section class="pending-page">
    <header class="page-heading"><div><h1>{{ t('deviceOnboarding.pending.title') }}</h1><p>{{ t(operationsMode ? 'deviceOnboarding.pending.operationsDescription' : 'deviceOnboarding.pending.description') }}</p></div></header>
    <ElAlert v-if="management.pendingError.value" :title="management.pendingError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="operationMessage" :title="operationMessage" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.productsError.value" :title="management.productsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.namingRulesError.value" :title="management.namingRulesError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.numericSourcesError.value" :title="management.numericSourcesError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="bindingContractError" :title="bindingContractError" type="error" show-icon :closable="false" />
    <ElAlert v-if="sensitiveError" :title="sensitiveError" type="error" show-icon :closable="false" />
    <div v-if="!operationsMode && protocolScope" class="scope-bar"><ElTag>{{ t('deviceOnboarding.labels.protocolScope') }} {{ protocolScope }}</ElTag><ElButton link @click="clearProtocolScope">{{ t('deviceOnboarding.actions.showAll') }}</ElButton><ElButton v-if="route.query.draftId" link @click="returnToDraft">{{ t('deviceOnboarding.actions.returnToDraft') }}</ElButton></div>
    <div v-if="administrator" class="device-view-switch">
      <ElTabs :model-value="activeDeviceView" :before-leave="() => !viewSwitchDisabled" @tab-change="changeDeviceView">
        <ElTabPane name="daikin" :label="t('deviceOnboarding.actions.daikinDevices')" />
        <ElTabPane name="general" :label="t('deviceOnboarding.actions.generalDevices')" />
      </ElTabs>
      <p>{{ t(operationsMode ? 'deviceOnboarding.pending.daikinTabDescription' : 'deviceOnboarding.pending.generalTabDescription') }}</p>
    </div>
    <ElCard v-if="operationsMode" class="sync-card" shadow="never">
      <div class="section-heading"><div><h2>{{ t('deviceOnboarding.pending.directorySync') }}</h2><p>{{ t('deviceOnboarding.pending.directorySyncDescription') }}</p></div></div>
      <ElAlert v-if="operationsManagement.daikinSourcesError.value" :title="operationsManagement.daikinSourcesError.value.message" type="error" show-icon :closable="false" />
      <ElAlert v-else-if="!operationsManagement.daikinSourcesLoading.value && !operationsManagement.daikinSources.value.length" :title="t('deviceOnboarding.empty.daikinSources')" type="info" show-icon :closable="false" />
      <div class="sync-form">
        <label for="daikin-source-id">{{ t('deviceOnboarding.labels.syncSource') }}</label>
        <div class="sync-actions"><ElSelect v-model="syncSourceId" input-id="daikin-source-id" :loading="operationsManagement.daikinSourcesLoading.value" :placeholder="t('deviceOnboarding.labels.syncSourcePlaceholder')"><ElOption v-for="source in operationsManagement.daikinSources.value" :key="source.sourceId" :label="source.sourceName" :value="source.sourceId" /></ElSelect><ElButton type="primary" :disabled="!syncSourceId" :loading="management.running.value.has(`daikin:sync:${syncSourceId}`)" @click="requestSync">{{ t('deviceOnboarding.actions.syncDirectory') }}</ElButton><ElButton v-if="management.syncJob.value" :icon="RefreshCw" :loading="management.running.value.has(`daikin:sync:status:${management.syncJob.value.jobId}`)" @click="refreshSync">{{ t('deviceOnboarding.actions.refreshSync') }}</ElButton><ElButton @click="openSyncHistory">{{ t('deviceOnboarding.actions.viewSyncHistory') }}</ElButton></div>
        <p class="field-hint">{{ t('deviceOnboarding.messages.syncSourceBoundary') }}</p>
      </div>
      <section v-if="management.syncJob.value" class="sync-result">
        <div class="sync-result-line" role="status">
          <strong class="sync-result-heading">{{ t('deviceOnboarding.pending.latestSync') }}</strong>
          <span class="sync-result-item sync-result-status"><span>{{ t('deviceOnboarding.labels.syncStatus') }}</span><ElTag>{{ syncJobStatusText(management.syncJob.value.status) }}</ElTag></span>
          <span class="sync-result-item sync-result-attempts"><span>{{ t('deviceOnboarding.labels.syncAttempts') }}</span><b>{{ formatNumber(management.syncJob.value.attempts) }}</b></span>
          <span class="sync-result-item sync-result-outcome" :class="{ 'is-warning': management.syncJob.value.errorCode }"><span>{{ t('deviceOnboarding.labels.syncResult') }}</span><b>{{ syncJobResultText(management.syncJob.value.errorCode) }}</b></span>
        </div>
        <details class="technical-details"><summary>{{ t('deviceOnboarding.operationsTechnicalDetails') }}</summary><ElDescriptions :column="1" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncSourceId')"><CopyableValue :value="management.syncJob.value.sourceId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncJobTechnicalId')"><CopyableValue :value="management.syncJob.value.jobId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncErrorCode')"><CopyableValue :value="management.syncJob.value.errorCode || t('common.missing')" /></ElDescriptionsItem></ElDescriptions></details>
      </section>
    </ElCard>
    <ElDrawer v-model="syncHistoryOpen" size="min(900px, 94vw)" :title="t('deviceOnboarding.pending.syncHistory')">
      <ElAlert :title="t('deviceOnboarding.messages.syncHistoryBoundary')" type="info" show-icon :closable="false" />
      <ElSkeleton v-if="operationsManagement.syncJobsLoading.value && !operationsManagement.syncJobs.value.items.length" animated :rows="5" />
      <ElAlert v-else-if="operationsManagement.syncJobsError.value" :title="operationsManagement.syncJobsError.value.message" type="error" show-icon :closable="false" />
      <ElTable v-else :data="operationsManagement.syncJobs.value.items" row-key="jobId" class="sync-history-table">
        <ElTableColumn :label="t('deviceOnboarding.labels.syncStartedAt')" min-width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.syncCompletedAt')" min-width="170"><template #default="{ row }">{{ row.completedAt ? formatDateTime(row.completedAt) : t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.syncStatus')" min-width="110"><template #default="{ row }"><ElTag>{{ syncJobStatusText(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.syncAttempts')" min-width="90"><template #default="{ row }">{{ formatNumber(row.attempts) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.syncResult')" min-width="220"><template #default="{ row }">{{ syncJobResultText(row.errorCode) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.operationsTechnicalDetails')" min-width="120"><template #default="{ row }"><details class="technical-details"><summary>{{ t('deviceOnboarding.actions.viewDetail') }}</summary><ElDescriptions :column="1" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncSourceId')"><CopyableValue :value="row.sourceId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncJobTechnicalId')"><CopyableValue :value="row.jobId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncErrorCode')"><CopyableValue :value="row.errorCode || t('common.missing')" /></ElDescriptionsItem></ElDescriptions></details></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('deviceOnboarding.empty.syncHistory')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="operationsManagement.syncJobs.value.page" :page-size="operationsManagement.syncJobs.value.size" :total="operationsManagement.syncJobs.value.total" @current-change="changeSyncHistoryPage" /></div>
    </ElDrawer>
    <ElCard shadow="never">
      <div class="filter-bar"><ElSelect v-model="management.pendingQuery.value.status" clearable :placeholder="t('deviceOnboarding.labels.status')" @change="query"><ElOption value="DISCOVERED" :label="t('deviceOnboarding.status.discovered')" /><ElOption value="IGNORED" :label="t('deviceOnboarding.status.ignored')" /><ElOption value="BOUND" :label="t('deviceOnboarding.status.bound')" /></ElSelect><ElInput v-if="!operationsMode" v-model="management.pendingQuery.value.identity" :placeholder="t('deviceOnboarding.labels.identity')" clearable @keyup.enter="query"><template #prefix><Search aria-hidden="true" /></template></ElInput><ElInput v-if="!operationsMode && !protocolScope" v-model="management.pendingQuery.value.profileCode" :placeholder="t('deviceOnboarding.labels.expectedProfile')" clearable @keyup.enter="query" /><ElButton :icon="Search" @click="query">{{ t('deviceOnboarding.actions.query') }}</ElButton><ElButton :icon="RefreshCw" @click="resetFilters">{{ t('deviceOnboarding.actions.reset') }}</ElButton><ElButton v-if="operationsMode" type="primary" :disabled="!selectedRows.length" @click="openBatchBinding">{{ t('deviceOnboarding.actions.batchBinding') }}</ElButton></div>
      <ElSkeleton v-if="management.pendingLoading.value && !management.pendingDevices.value.items.length" animated :rows="5" />
      <ElTable v-else :data="management.pendingDevices.value.items" row-key="pendingId" @selection-change="changeSelection">
        <ElTableColumn v-if="operationsMode" type="selection" width="48" />
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="maskedIdentityValue" min-width="200" />
        <ElTableColumn :label="t('deviceOnboarding.labels.expectedProfile')" min-width="150"><template #default="{ row }">{{ operationsMode ? profileText(row.profileCode) : row.profileCode }}</template></ElTableColumn>
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
      <template v-else-if="selectedPending"><div class="drawer-heading"><div><h2>{{ operationsMode ? (management.selectedDirectory.value?.deviceName || t('deviceOnboarding.pending.daikinDevice')) : selectedPending.identityValue }}</h2><p>{{ operationsMode ? profileText(selectedPending.profileCode) : selectedPending.profileCode }}</p></div><PendingStatusTag :status="selectedPending.status" /></div><ElDescriptions :column="2" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.identityType')">{{ identityTypeText(selectedPending.identityType) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.profileVersion')">{{ formatNumber(selectedPending.lastProfileVersion) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.reportCount')">{{ formatNumber(selectedPending.reportCount) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.firstSeen')">{{ formatDateTime(selectedPending.firstSeenTime) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.lastSeen')">{{ formatDateTime(selectedPending.lastSeenTime) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.sampleTruncated')">{{ selectedPending.sampleTruncated ? t('deviceOnboarding.labels.enabled') : t('common.missing') }}</ElDescriptionsItem></ElDescriptions><section class="sample-section"><h3>{{ t('deviceOnboarding.pending.connection') }}</h3><ElSkeleton v-if="management.pendingConnectionLoading.value" animated :rows="3" /><ElAlert v-else-if="management.pendingConnectionError.value" :title="management.pendingConnectionError.value.message" type="error" show-icon :closable="false" /><ElDescriptions v-else-if="management.pendingConnection.value" :column="2" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.identityStatus')"><ElTag :type="management.pendingConnection.value.identityStatus === 'ACTIVE' ? 'success' : 'info'">{{ t(`deviceOnboarding.identityStatus.${management.pendingConnection.value.identityStatus}`) }}</ElTag></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.configEffective')">{{ management.pendingConnection.value.configEffective ? t('deviceOnboarding.labels.cacheConsistent') : t('deviceOnboarding.labels.cacheInconsistent') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.equipmentId')">{{ management.pendingConnection.value.equipmentId || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.productId')">{{ management.pendingConnection.value.productId || t('common.missing') }}</ElDescriptionsItem></ElDescriptions><ElAlert :title="t('deviceOnboarding.messages.connectionBoundary')" type="info" show-icon :closable="false" /></section><section class="sample-section"><h3>{{ t('deviceOnboarding.pending.latestSample') }}</h3><pre>{{ latestMetrics }}</pre></section><div class="drawer-actions"><ElPopconfirm v-if="can(selectedPending, 'IGNORE')" :title="t('deviceOnboarding.messages.ignoreConfirm')" :confirm-button-text="t('deviceOnboarding.actions.ignore')" :cancel-button-text="t('deviceOnboarding.actions.cancel')" @confirm="ignore(selectedPending)"><template #reference><ElButton type="warning">{{ t('deviceOnboarding.actions.ignore') }}</ElButton></template></ElPopconfirm><ElButton v-if="can(selectedPending, 'RESTORE')" @click="restore(selectedPending)">{{ t('deviceOnboarding.actions.restore') }}</ElButton><ElButton v-if="can(selectedPending, 'BIND')" type="primary" :icon="Settings2" @click="openBinding">{{ t('deviceOnboarding.actions.prepareBinding') }}</ElButton><ElButton v-if="management.pendingConnection.value?.identityId && management.pendingConnection.value.identityStatus !== 'ACTIVE'" type="primary" :loading="identityChangeSubmitting('ACTIVATE_DEVICE_IDENTITY', management.pendingConnection.value.identityId)" @click="submitIdentityChange('ACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.activateIdentity') }}</ElButton><ElButton v-if="management.pendingConnection.value?.identityId && management.pendingConnection.value.identityStatus === 'ACTIVE'" type="warning" :loading="identityChangeSubmitting('DEACTIVATE_DEVICE_IDENTITY', management.pendingConnection.value.identityId)" @click="submitIdentityChange('DEACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.deactivateIdentity') }}</ElButton><ElButton v-if="management.pendingConnection.value?.equipmentId" @click="openReadings">{{ t('deviceOnboarding.actions.viewReadings') }}</ElButton></div><ElAlert v-if="bindingSubmitError" :title="bindingSubmitError" type="error" show-icon :closable="false" /><ChangeRequestControl business-view :change="sensitiveChange.current.value" :busy="sensitiveChange.pending.value.size > 0" @lookup="id => afterApprovalAction(() => sensitiveChange.load(id))" @submit="id => afterApprovalAction(() => sensitiveChange.submit(id))" @withdraw="id => afterApprovalAction(() => sensitiveChange.withdraw(id))" @approve="(id, comment) => afterApprovalAction(() => sensitiveChange.approve(id, comment))" @reject="(id, comment) => afterApprovalAction(() => sensitiveChange.reject(id, comment))" @execute="id => afterApprovalAction(() => sensitiveChange.execute(id), true)" /></template>
      <section v-if="operationsMode && management.selectedDirectory.value" class="sample-section">
        <h3>{{ t('deviceOnboarding.pending.daikinDirectory') }}</h3>
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.daikinKind')">{{ t(`deviceOnboarding.daikinKind.${management.selectedDirectory.value.kind}`) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.siteName')">{{ management.selectedDirectory.value.siteName || management.selectedDirectory.value.siteId }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.deviceName')">{{ management.selectedDirectory.value.deviceName || management.selectedDirectory.value.unitId }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.observedAt')">{{ formatDateTime(management.selectedDirectory.value.observedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.directoryState')"><ElTag :type="management.selectedDirectory.value.missing ? 'warning' : 'success'">{{ t(management.selectedDirectory.value.missing ? 'deviceOnboarding.directory.missing' : 'deviceOnboarding.directory.present') }}</ElTag></ElDescriptionsItem>
        </ElDescriptions>
        <details class="technical-details"><summary>{{ t('deviceOnboarding.operationsTechnicalDetails') }}</summary><ElDescriptions :column="1" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncSourceId')"><CopyableValue :value="management.selectedDirectory.value.sourceId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.controllerId')"><CopyableValue :value="management.selectedDirectory.value.controllerId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.equipmentId')"><CopyableValue :value="management.selectedDirectory.value.equipmentId || t('common.missing')" /></ElDescriptionsItem></ElDescriptions></details>
        <div class="drawer-actions"><ElButton type="primary" :loading="management.running.value.has(`daikin:sync:${management.selectedDirectory.value.sourceId}`)" @click="requestDirectorySync">{{ t('deviceOnboarding.actions.syncDirectory') }}</ElButton><ElButton v-if="management.syncJob.value" :loading="management.running.value.has(`daikin:sync:status:${management.syncJob.value.jobId}`)" @click="refreshSync">{{ t('deviceOnboarding.actions.refreshSync') }}</ElButton></div>
        <ElDescriptions v-if="management.syncJob.value" :column="3" border>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.syncStatus')"><ElTag>{{ syncJobStatusText(management.syncJob.value.status) }}</ElTag></ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.syncAttempts')">{{ formatNumber(management.syncJob.value.attempts) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.syncResult')">{{ syncJobResultText(management.syncJob.value.errorCode) }}</ElDescriptionsItem>
        </ElDescriptions>
        <details v-if="management.syncJob.value" class="technical-details"><summary>{{ t('deviceOnboarding.operationsTechnicalDetails') }}</summary><ElDescriptions :column="1" border><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncSourceId')"><CopyableValue :value="management.syncJob.value.sourceId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncJobTechnicalId')"><CopyableValue :value="management.syncJob.value.jobId" /></ElDescriptionsItem><ElDescriptionsItem :label="t('deviceOnboarding.labels.syncErrorCode')"><CopyableValue :value="management.syncJob.value.errorCode || t('common.missing')" /></ElDescriptionsItem></ElDescriptions></details>
        <ElAlert :title="t('deviceOnboarding.messages.syncBoundary')" type="info" show-icon :closable="false" />
      </section>
    </ElDrawer>

    <BindingDraftDialog :open="bindingOpen" :pending="selectedPending" :products="management.products.value.items" :product="bindingProduct" :product-loading="management.productsLoading.value || management.productDetailLoading.value" :product-total="management.products.value.total" :product-page="management.products.value.page" :product-size="management.products.value.size" :naming-rules="management.namingRules.value" :naming-rules-loading="management.namingRulesLoading.value" :numeric-sources="management.numericSources.value" :numeric-sources-loading="management.numericSourcesLoading.value" :binding-options="operationsMode ? management.bindingOptions.value : null" :submitting="bindingSubmitting" :submit-error="bindingSubmitError" :allow-empty-points="operationsMode" :product-search-enabled="!operationsMode" @close="bindingOpen = false" @product-change="selectProduct" @product-search="searchBindingProducts" @product-page-change="changeBindingProductPage" @binding-scope-change="(spaceId, systemGroupId) => loadBindingEquipment(1, spaceId, systemGroupId)" @equipment-page-change="loadBindingEquipment" @submit="submitBindingDraft" />

    <ElDialog v-model="batchResultsOpen" :title="t('deviceOnboarding.pending.bindingResults')" width="min(760px, 94vw)">
      <ElAlert :title="t('deviceOnboarding.messages.batchBoundary')" type="info" show-icon :closable="false" />
      <ElTable :data="management.bindingApplications.value" row-key="pendingId">
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="pendingId" min-width="180" />
        <ElTableColumn :label="t('deviceOnboarding.labels.requestId')" min-width="180"><template #default="{ row }">{{ asBindingApplication(row).requestId || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" prop="status" min-width="130" />
        <ElTableColumn :label="t('deviceOnboarding.labels.errorCode')" min-width="160"><template #default="{ row }">{{ asBindingApplication(row).errorCode || t('common.missing') }}</template></ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="readingsOpen" :title="t('deviceOnboarding.pending.readings')" width="min(900px, 94vw)" @closed="equipmentReadings.clear()"><ElSkeleton v-if="equipmentReadings.loading.value" animated :rows="5" /><ElAlert v-else-if="equipmentReadings.error.value" :title="equipmentReadings.error.value" type="error" show-icon :closable="false" /><template v-else-if="equipmentReadings.readings.value"><ElAlert :title="t('deviceOnboarding.messages.readingsBoundary')" type="info" show-icon :closable="false" /><ElTable :data="equipmentReadings.readings.value.points" row-key="pointId"><ElTableColumn :label="t('deviceOnboarding.labels.readingPointName')" prop="pointName" min-width="150" /><ElTableColumn :label="t('deviceOnboarding.labels.readingPointCode')" prop="pointCode" min-width="140" /><ElTableColumn :label="t('deviceOnboarding.labels.readingValue')" min-width="120"><template #default="{ row }">{{ row.value == null ? t('common.missing') : `${formatNumber(row.value)} ${row.unit === '1' ? '' : (row.unit || '')}` }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.readingStatus')" min-width="130"><template #default="{ row }">{{ readingStatusText(row.status) }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.eventTime')" min-width="180"><template #default="{ row }">{{ row.eventTime ? formatDateTime(row.eventTime) : t('common.missing') }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.reason')" min-width="240"><template #default="{ row }">{{ readingReasonText(row.reason) }}<details><summary>{{ t('deviceOnboarding.technicalDetails') }}</summary><div>{{ `${row.status} / ${row.usageStatus}` }}</div><div>{{ row.reason || t('common.missing') }}</div></details></template></ElTableColumn></ElTable></template></ElDialog>
  </section>
</template>

<style scoped>
.pending-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .drawer-heading, .filter-bar, .row-actions, .drawer-actions, .section-heading, .sync-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .drawer-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.device-view-switch { display: grid; gap: var(--bec-space-tight); }
.device-view-switch :deep(.el-tabs__header) { margin: 0; }
.device-view-switch p { font-size: var(--bec-font-size-small); }
.sync-card :deep(.el-card__body) { display: grid; gap: var(--bec-space-section); }
.section-heading { align-items: flex-start; justify-content: space-between; }
.section-heading p { margin-top: var(--bec-space-tight); }
.sync-form, .sync-result { display: grid; gap: var(--bec-space-tight); }
.sync-form { gap: var(--bec-space-tight); }
.sync-form label { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.sync-actions { align-items: stretch; flex-wrap: wrap; gap: var(--bec-space-tight); }
.sync-actions > :deep(.el-input), .sync-actions > :deep(.el-select) { flex: 1 1 calc(var(--bec-navigation-width) * 1.5); min-width: var(--bec-navigation-width); }
.field-hint { font-size: var(--bec-font-size-small); }
.sync-result { padding-top: var(--bec-space-group); border-top: var(--bec-border-width) solid var(--bec-color-divider); }
.sync-result-line { display: grid; grid-template-columns: auto repeat(3, minmax(0, 1fr)); align-items: stretch; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-card); }
.sync-result-heading { align-self: center; padding-inline: var(--bec-space-tight) var(--bec-space-group); white-space: nowrap; }
.sync-result-item { display: grid; align-content: center; gap: calc(var(--bec-space-tight) / 2); min-height: var(--bec-ref-space-48); padding: var(--bec-space-tight) var(--bec-space-group); color: var(--bec-color-text-secondary); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-control); }
.sync-result-item > span { font-size: var(--bec-font-size-small); }
.sync-result-item b { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.sync-result-status { background: color-mix(in srgb, var(--bec-color-action-primary) 5%, var(--bec-color-surface)); }
.sync-result-attempts { background: color-mix(in srgb, var(--bec-color-action-primary) 3%, var(--bec-color-surface)); }
.sync-result-outcome { background: color-mix(in srgb, var(--bec-color-success) 7%, var(--bec-color-surface)); }
.sync-result-outcome.is-warning { background: color-mix(in srgb, var(--bec-color-warning) 9%, var(--bec-color-surface)); }
.filter-bar { align-items: stretch; }
.filter-bar { flex-wrap: wrap; }
.filter-bar > :deep(.el-input) { flex: 1 1 calc(var(--bec-navigation-width) * 1.5); min-width: var(--bec-navigation-width); order: -1; }
.filter-bar :deep(.el-input__prefix svg) { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.filter-bar > :deep(.el-select) { flex: 0 0 calc(var(--bec-navigation-width) * 0.75); width: calc(var(--bec-navigation-width) * 0.75); min-width: 0; }
@media (max-width: 640px) {
  .filter-bar > :deep(.el-input), .sync-actions > :deep(.el-input), .sync-actions > :deep(.el-select) { flex-basis: 100%; min-width: 0; }
  .sync-actions > :deep(.el-button) { flex: 1 1 auto; margin-left: 0; }
  .sync-result-line { grid-template-columns: 1fr; }
  .sync-result-heading { padding: 0 0 var(--bec-space-tight); }
}
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.drawer-actions { justify-content: flex-end; margin-top: var(--bec-space-section); }
.row-actions { gap: var(--bec-space-tight); flex-wrap: wrap; }
.sample-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.technical-details { margin-top: var(--bec-space-group); color: var(--bec-color-text-secondary); }
.technical-details summary { width: fit-content; cursor: pointer; color: var(--bec-color-text-secondary); }
.technical-details :deep(.el-descriptions) { margin-top: var(--bec-space-tight); }
.sync-history-table { margin-top: var(--bec-space-section); }
pre { margin: 0; padding: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); overflow: auto; }
</style>
