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
  Eye,
  RefreshCw,
  Search,
  Settings2,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { identityTypeText, profileText, readingReasonText, readingStatusText, syncJobResultText, syncJobStatusText } from '../models/reading-labels'
import { ChangeRequestControl, newIdempotencyKey, useSensitiveChange } from '@/modules/access-control/public'
import { useEquipmentReadings } from '@/modules/asset-management/public'
import { useSession } from '@/modules/auth/public'
import BindingDraftDialog from '../components/BindingDraftDialog.vue'
import TemperatureBatchDialog from '../components/TemperatureBatchDialog.vue'
import { getOperationsPendingConnection } from '../api/onboarding'
import { IndoorBatchBindingError, prepareIndoorBatchBindings } from '../models/indoor-batch-binding'
import PendingStatusTag from '../components/PendingStatusTag.vue'
import { useDeviceOnboarding } from '../composables/use-device-onboarding'
import {
  canRunOnboardingAction,
  type OperationsBindingApplication,
  type PendingBindRequest,
  type PendingDevice,
  type PendingSourceScope,
  type TemperatureBatchRequestItem,
  type TemperatureInitializationPreview,
  type TemperaturePlanSelection,
  type TemperaturePreviewItem,
  type TemperatureRuleDraft,
} from '../models/onboarding'

const session = useSession()
const route = useRoute()
const router = useRouter()
const administrator = computed(() => session.user?.roles.includes('PLATFORM_ADMIN') === true)

function parseSourceScope(value: unknown, hasProfileCode: boolean): PendingSourceScope {
  if (value === 'STANDARD' || value === 'DAIKIN' || value === 'ALL') return value
  return hasProfileCode ? 'STANDARD' : 'ALL'
}

const initialProfileCode = typeof route.query.profileCode === 'string' ? route.query.profileCode : ''
const protocolScope = ref(initialProfileCode)
const sourceScope = ref<PendingSourceScope>(parseSourceScope(route.query.sourceScope, Boolean(initialProfileCode)))

const generalManagement = useDeviceOnboarding()
const operationsManagement = useDeviceOnboarding({ operations: true })
// 管理员统一列表走服务端过滤分页；非管理员及大金详情/绑定走授权域服务。
const listManagement = computed(() => administrator.value ? generalManagement : operationsManagement)
const activeItemDaikin = ref(false)
const detailManagement = computed(() => (!administrator.value || activeItemDaikin.value) ? operationsManagement : generalManagement)

const showDaikinSyncBar = computed(() => !administrator.value || sourceScope.value !== 'STANDARD')
const showDaikinColumns = computed(() => !administrator.value || sourceScope.value === 'DAIKIN')
const showDaikinBatch = computed(() => !administrator.value || sourceScope.value === 'DAIKIN')
const isDetailDaikin = computed(() => !administrator.value || activeItemDaikin.value)

const sensitiveChange = useSensitiveChange()
const equipmentReadings = useEquipmentReadings()
const detailOpen = ref(false)
const bindingOpen = ref(false)
const readingsOpen = ref(false)
const bindingContractError = ref<string | null>(null)
const bindingSubmitError = ref<string | null>(null)
const automaticMappingRejected = ref(false)
const batchPendingIds = ref<string[]>([])
const batchRows = computed(() => batchPendingIds.value.length > 1
  ? selectedRows.value.filter(row => batchPendingIds.value.includes(row.pendingId)) : [])
const bindingKeys = new Map<string, string>()
const identityKeys = new Map<string, string>()
const batchResultsOpen = ref(false)
const temperatureBatchOpen = ref(false)
const temperatureBatchRows = ref<PendingDevice[]>([])
const temperatureBatchIdempotencyKey = ref<string | null>(null)
const activationBusy = ref(false)
const activationOpen = ref(false)
const activationRows = ref<PendingDevice[]>([])
const activationResultsOpen = ref(false)
const activationResults = ref<Array<{ pendingId: string; label: string; result: string; success: boolean }>>([])
const syncSourceId = ref('')
const syncHistoryOpen = ref(false)
const selectedRows = ref<PendingDevice[]>([])

const selectedPending = computed(() => detailManagement.value.selectedPending.value)
const bindingProduct = computed(() => isDetailDaikin.value
  ? operationsManagement.selectedBindingProduct.value
  : generalManagement.selectedProduct.value)
const bindingSubmitting = computed(() => {
  const pendingId = selectedPending.value?.pendingId
  if (isDetailDaikin.value) {
    return operationsManagement.running.value.has(batchPendingIds.value.length > 1 ? 'pending:binding:batch' : `pending:binding:${pendingId}`)
  }
  return pendingId ? sensitiveChange.isPending(`start:BIND_PENDING_DEVICE:${pendingId}`) : false
})
const sensitiveError = computed(() => sensitiveChange.error.value)
const operationMessage = computed(() => {
  const error = detailManagement.value.operationError.value ?? listManagement.value.operationError.value
  if (error?.code === 'DAIKIN_SYNC_PROVIDER_UNAVAILABLE') return t('deviceOnboarding.messages.syncProviderUnavailable')
  if (error?.code === 'DAIKIN_SYNC_DISABLED') return t('deviceOnboarding.messages.syncDisabled')
  return error?.message
})
const latestMetrics = computed(() => {
  const metrics = selectedPending.value?.latestMetrics
  return metrics == null ? t('common.missing') : JSON.stringify(metrics, null, 2)
})
const latestSyncSummary = computed(() => {
  const job = operationsManagement.syncJob.value ?? operationsManagement.syncJobs.value.items[0] ?? null
  return job
})

function returnToDraft() {
  void router.push({
    path: '/configuration/ingestion/protocols',
    query: { draftId: typeof route.query.draftId === 'string' ? route.query.draftId : undefined },
  })
}

function clearProtocolScope() {
  protocolScope.value = ''
  generalManagement.pendingQuery.value.profileCode = ''
  void query()
}

async function selectSourceScope(next: PendingSourceScope) {
  if (!administrator.value || sourceScope.value === next) return
  sourceScope.value = next
  selectedRows.value = []
  batchPendingIds.value = []
  await router.replace({ query: { ...route.query, sourceScope: next === 'ALL' ? undefined : next } })
  await query()
  if (next !== 'STANDARD' && !operationsManagement.daikinSources.value.length) {
    void loadDaikinSyncMeta()
  }
}

async function query() {
  try {
    if (administrator.value) {
      await generalManagement.setPendingQuery({
        status: generalManagement.pendingQuery.value.status,
        identity: generalManagement.pendingQuery.value.identity,
        profileCode: generalManagement.pendingQuery.value.profileCode,
        sourceScope: sourceScope.value,
      })
    } else {
      await operationsManagement.setPendingQuery({
        status: operationsManagement.pendingQuery.value.status,
      })
    }
  } catch {
    // 查询错误保留在 pendingError。
  }
}

async function resetFilters() {
  if (administrator.value) {
    generalManagement.pendingQuery.value = {
      page: 1,
      size: generalManagement.pendingQuery.value.size,
      status: undefined,
      identity: '',
      profileCode: protocolScope.value,
      sourceScope: sourceScope.value,
    }
  } else {
    operationsManagement.pendingQuery.value = {
      page: 1,
      size: operationsManagement.pendingQuery.value.size,
      status: undefined,
      identity: '',
      profileCode: '',
      sourceScope: undefined,
    }
  }
  try {
    await listManagement.value.loadPendingDevices()
  } catch {
    // 查询错误保留在 pendingError。
  }
}

function isDaikinRow(row: Pick<PendingDevice, 'identityType'>) {
  return row.identityType === 'DAIKIN_UNIT'
}

async function openDetail(row: Pick<PendingDevice, 'pendingId' | 'identityType'>) {
  activeItemDaikin.value = isDaikinRow(row)
  detailOpen.value = true
  try {
    await Promise.all([
      detailManagement.value.selectPending(row.pendingId),
      detailManagement.value.loadPendingConnection(row.pendingId).catch(() => undefined),
    ])
  } catch {
    detailOpen.value = false
  }
}

async function ignore(item: Pick<PendingDevice, 'pendingId' | 'identityType'>) {
  const target = (!administrator.value || isDaikinRow(item)) ? operationsManagement : generalManagement
  try {
    await target.changePendingStatus(item.pendingId, 'IGNORED')
    if (selectedPending.value?.pendingId === item.pendingId) await target.selectPending(item.pendingId)
    await listManagement.value.loadPendingDevices().catch(() => undefined)
    ElMessage.success(t('deviceOnboarding.messages.ignored'))
  } catch {
    // 错误已由状态展示。
  }
}

async function restore(item: Pick<PendingDevice, 'pendingId' | 'identityType'>) {
  const target = (!administrator.value || isDaikinRow(item)) ? operationsManagement : generalManagement
  try {
    await target.changePendingStatus(item.pendingId, 'DISCOVERED')
    if (selectedPending.value?.pendingId === item.pendingId) await target.selectPending(item.pendingId)
    await listManagement.value.loadPendingDevices().catch(() => undefined)
    ElMessage.success(t('deviceOnboarding.messages.restored'))
  } catch {
    // 错误已由状态展示。
  }
}

async function openBinding(ids?: string[]) {
  bindingSubmitError.value = null
  automaticMappingRejected.value = false
  const pending = selectedPending.value
  if (!pending) return
  batchPendingIds.value = ids ?? [pending.pendingId]
  ensureBindingKeys(batchPendingIds.value)
  try {
    bindingContractError.value = null
    detailManagement.value.clearTemperaturePlans()
    await loadBindingProducts({
      page: 1,
      size: 20,
      status: 'ENABLED',
      keyword: '',
      expectedProfileCode: pending.profileCode,
      identityType: pending.identityType,
    })
    if (isDetailDaikin.value) {
      await Promise.all([operationsManagement.loadNumericSources(), operationsManagement.loadBindingOptions()])
      if (isTemperatureEligible(pending)) void operationsManagement.loadTemperatureOptions(pending.pendingId).catch(() => undefined)
    } else {
      await generalManagement.loadNamingRules()
    }
    bindingOpen.value = true
  } catch {
    bindingSubmitError.value = t('deviceOnboarding.messages.bindingPreparationFailed')
  }
}

async function loadBindingProducts(patch: Partial<typeof generalManagement.productQuery.value>, resetPage = true) {
  const target = detailManagement.value
  await (isDetailDaikin.value ? target.selectBindingProduct(null) : target.selectProduct(null))
  const result = await target.setProductQuery(patch, resetPage)
  if (toRaw(target.products.value) === result && result.total === 1 && result.items[0]) {
    await selectProduct(result.items[0].productId)
  }
}

async function searchBindingProducts(keyword: string) {
  if (isDetailDaikin.value) return
  try {
    await loadBindingProducts({ keyword })
  } catch {
    // 错误已保留在 composable。
  }
}

async function changeBindingProductPage(page: number) {
  try {
    await loadBindingProducts({ page }, false)
  } catch {
    // 错误已保留在 composable。
  }
}

async function loadBindingEquipment(page: number, spaceId?: string, systemGroupId?: string) {
  try {
    await operationsManagement.loadBindingOptions({ page, size: 20, spaceId, systemGroupId })
  } catch {
    // 运维绑定候选由后端分页返回。
  }
}

async function openBindingFor(item: PendingDevice) {
  await openDetail(item)
  if (detailManagement.value.selectedPending.value?.pendingId === item.pendingId) await openBinding()
}

async function selectProduct(productId: string) {
  try {
    if (isDetailDaikin.value) operationsManagement.clearTemperaturePlans()
    bindingContractError.value = null
    const detail = await (isDetailDaikin.value
      ? operationsManagement.selectBindingProduct(productId)
      : generalManagement.selectProduct(productId))
    const pending = selectedPending.value
    if (detail && pending && (detail.status !== 'ENABLED' || detail.expectedProfileCode !== pending.profileCode || detail.identityType !== pending.identityType)) {
      await detailManagement.value.selectProduct(null)
      bindingContractError.value = t('deviceOnboarding.validation.bindingProductContract')
    }
    if (isDetailDaikin.value) await loadBindingEquipment(1)
  } catch {
    // 产品详情失败保留在状态中。
  }
}

async function previewBindingTemperature(value: TemperaturePreviewItem) {
  const pending = selectedPending.value
  const request = value.binding
  if (!pending || !request || !isDetailDaikin.value) return
  try {
    const items = bindingItemsForTemperature(request).map(item => ({
      pendingId: item.pendingId,
      mode: value.mode,
      ...(value.templateProductId ? { templateProductId: value.templateProductId } : {}),
      ...(value.numericSourceId ? { numericSourceId: value.numericSourceId } : {}),
      ...(value.existingPointIds ? { existingPointIds: value.existingPointIds } : {}),
      binding: item.binding,
    }))
    await operationsManagement.previewTemperaturePlans(items)
  } catch (reason) {
    bindingSubmitError.value = reason instanceof IndoorBatchBindingError
      ? t(`deviceOnboarding.messages.batchErrors.${reason.reason}`) : requestErrorMessage(reason)
  }
}

function bindingItemsForTemperature(request: PendingBindRequest) {
  const pending = selectedPending.value
  if (!pending) return []
  return batchPendingIds.value.length > 1
    ? prepareIndoorBatchBindings(batchRows.value, request, t('deviceOnboarding.messages.batchEquipmentNamePrefix'))
    : [{ pendingId: pending.pendingId, binding: request }]
}

function bindTemperaturePlan(request: PendingBindRequest, pendingId: string): PendingBindRequest {
  if (request.temperatureMode !== 'AUTO' && request.temperatureMode !== 'MANUAL') return request
  const plan = operationsManagement.temperaturePlans.value.find(item => item.pendingId === pendingId)
  if (!plan?.digest || !plan.numericSourceId || !['READY', 'COMPLETE'].includes(plan.status)) {
    throw new Error(t('deviceOnboarding.validation.temperaturePreview'))
  }
  if (request.temperatureMode === 'AUTO') {
    const withoutTemplate = { ...request }
    delete withoutTemplate.temperatureTemplateProductId
    return { ...withoutTemplate, numericSourceId: plan.numericSourceId, temperaturePlanDigest: plan.digest }
  }
  return {
    ...request,
    numericSourceId: plan.numericSourceId,
    temperatureTemplateProductId: plan.templateProductId ?? request.temperatureTemplateProductId,
    temperaturePlanDigest: plan.digest,
  }
}

async function submitBindingDraft(request: PendingBindRequest) {
  bindingSubmitError.value = null
  automaticMappingRejected.value = false
  const pending = selectedPending.value
  if (!pending) return
  try {
    if (isDetailDaikin.value) {
      const ids = batchPendingIds.value.length ? batchPendingIds.value : [pending.pendingId]
      ensureBindingKeys(ids)
      const result = ids.length > 1
        ? await operationsManagement.submitBindingBatch(bindingItemsForTemperature(request).map(item => ({
            ...item, binding: bindTemperaturePlan(item.binding, item.pendingId),
          })), bindingKeys)
        : await operationsManagement.submitBindingRequest(ids[0]!, bindTemperaturePlan(request, ids[0]!), bindingKeys.get(ids[0]!)!)
      if (!result) return
      bindingOpen.value = false
      batchResultsOpen.value = true
      return
    }
    const payload: Record<string, unknown> = {
      pendingId: pending.pendingId,
      productId: request.productId,
      buildingId: request.buildingId,
      spaceId: request.spaceId,
      systemGroupId: request.systemGroupId,
      existingEquipmentId: request.existingEquipmentId,
      newEquipment: request.newEquipment,
      pointBindings: request.pointBindings,
      autoCreatePoints: request.autoCreatePoints,
      ...(request.activateOnApprove ? { activateOnApprove: true } : {}),
    }
    const change = await sensitiveChange.start(
      'BIND_PENDING_DEVICE',
      payload,
      `BIND_PENDING_DEVICE:${pending.pendingId}`,
    )
    if (!change) return
    bindingOpen.value = false
    ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch (reason) {
    if (reason instanceof IndoorBatchBindingError) {
      bindingSubmitError.value = t(`deviceOnboarding.messages.batchErrors.${reason.reason}`)
      return
    }
    const code = requestErrorCode(reason)
    automaticMappingRejected.value = code === 'ONBOARDING_AUTO_POINT_MAPPING_REQUIRED'
    bindingSubmitError.value = code === 'ONBOARDING_SOURCE_REQUIRED'
      ? t('deviceOnboarding.messages.sourceRequired')
      : code === 'ONBOARDING_SOURCE_AMBIGUOUS'
        ? t('deviceOnboarding.messages.sourceAmbiguous') : requestErrorMessage(reason)
  }
}

async function retryEffective(pendingId: string) {
  try {
    const connection = await detailManagement.value.refreshEffective(pendingId)
    if (connection?.configEffective) {
      ElMessage.success(t('deviceOnboarding.labels.cacheEffective'))
      await listManagement.value.loadPendingDevices().catch(() => undefined)
    }
  } catch {
    // 错误已由统一状态展示。
  }
}

async function openBatchBinding() {
  const rows = selectedRows.value
  const first = rows[0]
  if (!first) return
  if (!batchBindingReady.value) {
    ElMessage.warning(t('deviceOnboarding.messages.batchIndoorOnly'))
    return
  }
  activeItemDaikin.value = true
  try {
    await operationsManagement.selectPending(first.pendingId)
    await openBinding(rows.map(item => item.pendingId))
  } catch {
    // 范围或兼容产品错误由页面状态展示。
  }
}

function isTemperatureEligible(row: Pick<PendingDevice, 'identityType' | 'profileCode'>) {
  return row.identityType === 'DAIKIN_UNIT' && row.profileCode === 'DAIKIN_INDOOR_V2'
}

function canBatchTemperatureCompletion(row: PendingDevice) {
  return row.status === 'BOUND' && isTemperatureEligible(row)
}

const batchTemperatureReady = computed(() => selectedRows.value.length > 0 && selectedRows.value.every(canBatchTemperatureCompletion))

async function openTemperatureBatch() {
  if (!batchTemperatureReady.value) {
    ElMessage.warning(t('deviceOnboarding.messages.temperatureBatchSelection'))
    return
  }
  temperatureBatchRows.value = [...selectedRows.value]
  temperatureBatchIdempotencyKey.value = newIdempotencyKey()
  operationsManagement.clearTemperaturePlans()
  operationsManagement.clearTemperatureInitializationPreview()
  operationsManagement.clearTemperatureBatchJob()
  temperatureBatchOpen.value = true
  try {
    await Promise.all([
      operationsManagement.loadTemperatureOptions(temperatureBatchRows.value[0]!.pendingId),
      operationsManagement.loadLatestTemperatureBatchJob(temperatureBatchRows.value[0]!.pendingId),
    ])
  } catch {
    // 弹窗保留受控错误。
  }
}

async function previewTemperatureBatch(selection: TemperaturePlanSelection) {
  if (!temperatureBatchRows.value.length) return
  try {
    await operationsManagement.previewTemperaturePlans(temperatureBatchRows.value.map(row => ({
      pendingId: row.pendingId,
      ...selection,
    })))
  } catch {
    // 预览失败保留在温度计划状态。
  }
}

async function previewTemperatureInitialization(templateProductId: string) {
  if (!temperatureBatchRows.value.length || !templateProductId) return
  operationsManagement.clearTemperaturePlans()
  try {
    await operationsManagement.previewTemperatureInitialization({
      pendingIds: temperatureBatchRows.value.map(row => row.pendingId),
      templateProductId,
    })
  } catch {
    // 失败保留在状态中。
  }
}

function clearTemperaturePreviews() {
  operationsManagement.clearTemperaturePlans()
  operationsManagement.clearTemperatureInitializationPreview()
}

async function submitTemperatureInitialization(preview: TemperatureInitializationPreview) {
  const idempotencyKey = temperatureBatchIdempotencyKey.value
  const pendingIds = temperatureBatchRows.value.map(row => row.pendingId)
  if (!idempotencyKey || !pendingIds.length || preview.expiresAt <= Date.now()) {
    operationsManagement.clearTemperatureInitializationPreview()
    return
  }
  try {
    await operationsManagement.createTemperatureInitializationJob({
      pendingIds,
      templateProductId: preview.templateProductId,
      digest: preview.digest,
      idempotencyKey,
    })
  } catch {
    // 同一幂等键可安全重试。
  }
}

async function submitTemperatureBatch(selection: TemperaturePlanSelection) {
  const key = temperatureBatchIdempotencyKey.value
  if (!key || !temperatureBatchRows.value.length) return
  const items: TemperatureBatchRequestItem[] = []
  for (const row of temperatureBatchRows.value) {
    const plan = operationsManagement.temperaturePlans.value.find(item => item.pendingId === row.pendingId)
    if (!plan?.digest || !plan.numericSourceId || !['READY', 'COMPLETE'].includes(plan.status)) return
    items.push({
      pendingId: row.pendingId,
      mode: selection.mode,
      ...(selection.templateProductId ? { templateProductId: selection.templateProductId } : {}),
      numericSourceId: plan.numericSourceId,
      ...(selection.existingPointIds ? { existingPointIds: selection.existingPointIds } : {}),
      digest: plan.digest,
    })
  }
  try {
    await operationsManagement.createTemperatureBatchJob(key, items)
  } catch {
    // 错误由任务结果区域展示。
  }
}

async function refreshTemperatureBatch() {
  const jobId = operationsManagement.temperatureBatchJob.value?.jobId
  if (!jobId) return
  try {
    await operationsManagement.loadTemperatureBatchJob(jobId)
  } catch {
    // 错误保留在弹窗内。
  }
}

async function retryTemperatureBatch(pendingIds: string[]) {
  const jobId = operationsManagement.temperatureBatchJob.value?.jobId
  if (!jobId || !pendingIds.length) return
  try {
    await operationsManagement.retryTemperatureBatchJob(jobId, pendingIds)
  } catch {
    // 错误由任务结果区域展示。
  }
}

function restartTemperatureBatch() {
  temperatureBatchIdempotencyKey.value = newIdempotencyKey()
  operationsManagement.clearTemperaturePlans()
  operationsManagement.clearTemperatureInitializationPreview()
  operationsManagement.clearTemperatureBatchJob()
}

async function submitTemperatureRule(rule: TemperatureRuleDraft) {
  try {
    await operationsManagement.submitTemperatureRuleRequest({ rule, idempotencyKey: newIdempotencyKey() })
  } catch {
    // 规则申请错误保留在弹窗内。
  }
}

function canBatchBind(row: PendingDevice) {
  return row.status === 'DISCOVERED' && row.identityType === 'DAIKIN_UNIT'
    && row.profileCode === 'DAIKIN_INDOOR_V2' && Boolean(row.location?.roomSpaceId && row.location.roomCode)
}

function canBatchActivate(row: PendingDevice) {
  return row.status === 'BOUND' && row.identityStatus === 'INACTIVE'
}

function selectableForBatch(row: PendingDevice) {
  return canBatchBind(row) || canBatchActivate(row) || canBatchTemperatureCompletion(row)
}

const batchBindingReady = computed(() => selectedRows.value.length > 1 && selectedRows.value.every(canBatchBind))
const batchActivationReady = computed(() => selectedRows.value.length > 1 && selectedRows.value.every(canBatchActivate))
function changeSelection(rows: PendingDevice[]) {
  selectedRows.value = rows
  batchPendingIds.value = rows.map(item => item.pendingId)
}

function ensureBindingKeys(ids: string[]) {
  for (const id of ids) if (!bindingKeys.has(id)) bindingKeys.set(id, newIdempotencyKey())
}

function activationLabel(row: PendingDevice) {
  return row.location?.roomCode || row.location?.assetReferenceCode || `${profileText(row.profileCode)}（${row.pendingId}）`
}

function openBatchActivation() {
  if (!batchActivationReady.value) {
    ElMessage.warning(t('deviceOnboarding.messages.batchActivationBoundOnly'))
    return
  }
  activationResults.value = []
  activationRows.value = [...selectedRows.value]
  activationOpen.value = true
}

async function submitBatchActivation() {
  if (activationBusy.value || !activationOpen.value) return
  const rows = [...activationRows.value]
  if (rows.length < 2 || rows.some(row => !canBatchActivate(row))) return
  activationBusy.value = true
  const results: typeof activationResults.value = []
  for (const row of rows) {
    const label = activationLabel(row)
    try {
      const connection = await getOperationsPendingConnection(row.pendingId)
      if (!connection.identityId) {
        results.push({ pendingId: row.pendingId, label, result: t('deviceOnboarding.messages.batchActivationUnbound'), success: false })
        continue
      }
      if (connection.identityStatus === 'ACTIVE') {
        results.push({ pendingId: row.pendingId, label, result: t('deviceOnboarding.messages.batchActivationAlreadyActive'), success: false })
        continue
      }
      const key = `${row.pendingId}:ACTIVE`
      if (!identityKeys.has(key)) identityKeys.set(key, newIdempotencyKey())
      const application = await operationsManagement.submitIdentityStatus(row.pendingId, 'ACTIVE', identityKeys.get(key)!)
      const status = application?.status
      results.push({ pendingId: row.pendingId, label,
        result: status === 'PENDING_REVIEW' ? t('deviceOnboarding.messages.batchActivationSubmitted')
          : status === 'APPROVED' ? t('deviceOnboarding.messages.batchActivationApproved')
            : status === 'EXECUTED' ? t('deviceOnboarding.messages.batchActivationExecuted')
              : t('deviceOnboarding.messages.batchActivationFailed'),
        success: Boolean(application?.requestId && ['PENDING_REVIEW', 'APPROVED', 'EXECUTED'].includes(status ?? '')) })
    } catch (reason) {
      results.push({ pendingId: row.pendingId, label, result: requestErrorMessage(reason), success: false })
    }
  }
  activationResults.value = results
  activationBusy.value = false
  activationOpen.value = false
  activationResultsOpen.value = true
  await listManagement.value.loadPendingDevices().catch(() => undefined)
  selectedRows.value = []
  batchPendingIds.value = []
}

async function requestSync() {
  const sourceId = syncSourceId.value
  if (!sourceId) {
    ElMessage.warning(t('deviceOnboarding.messages.syncSourceRequired'))
    return
  }
  try {
    await operationsManagement.startDirectorySync(sourceId)
  } catch {
    // 错误保留在统一状态。
  }
}

async function refreshSync() {
  try {
    await operationsManagement.refreshDirectorySync()
  } catch {
    // 错误保留在统一状态。
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

async function submitIdentityChange(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY') {
  const identityId = detailManagement.value.pendingConnection.value?.identityId
  const pendingId = selectedPending.value?.pendingId
  if (!identityId || !pendingId) return
  try {
    if (isDetailDaikin.value) {
      const targetStatus = operation === 'ACTIVATE_DEVICE_IDENTITY' ? 'ACTIVE' : 'INACTIVE'
      const key = `${pendingId}:${targetStatus}`
      if (!identityKeys.has(key)) identityKeys.set(key, newIdempotencyKey())
      const application = await operationsManagement.submitIdentityStatus(pendingId, targetStatus, identityKeys.get(key)!)
      if (application) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
      return
    }
    const change = await sensitiveChange.start(operation, { identityId }, `${operation}:${identityId}`)
    if (change) ElMessage.success(t('deviceOnboarding.messages.changeSubmitted'))
  } catch {
    // 权限模块已转换错误。
  }
}

async function afterApprovalAction(action: () => Promise<unknown>, execute = false) {
  try {
    await action()
    if (execute && selectedPending.value) {
      await Promise.all([
        listManagement.value.loadPendingDevices(),
        detailManagement.value.selectPending(selectedPending.value.pendingId),
        detailManagement.value.loadPendingConnection(selectedPending.value.pendingId),
      ])
    }
  } catch {
    // 公共审批能力保留标准错误。
  }
}

async function openReadings() {
  const equipmentId = detailManagement.value.pendingConnection.value?.equipmentId
  if (!equipmentId) return
  readingsOpen.value = true
  try {
    await equipmentReadings.load(equipmentId)
  } catch {
    // 资产模块统一转换读数查询错误。
  }
}

function identityChangeSubmitting(operation: 'ACTIVATE_DEVICE_IDENTITY' | 'DEACTIVATE_DEVICE_IDENTITY', identityId: string) {
  if (isDetailDaikin.value && selectedPending.value) {
    const target = operation === 'ACTIVATE_DEVICE_IDENTITY' ? 'ACTIVE' : 'INACTIVE'
    return operationsManagement.running.value.has(`pending:identity:${selectedPending.value.pendingId}:${target}`)
  }
  return sensitiveChange.isPending(`start:${operation}:${identityId}`)
}

function can(value: unknown, action: string) {
  return canRunOnboardingAction(value as { allowedActions?: string[] }, action)
}

function asPending(value: unknown): PendingDevice { return value as PendingDevice }

function rowProfileLabel(row: Pick<PendingDevice, 'identityType' | 'profileCode'>) {
  return row.identityType === 'DAIKIN_UNIT' ? profileText(row.profileCode) : row.profileCode
}

async function loadDaikinSyncMeta() {
  await Promise.all([
    operationsManagement.loadDirectorySyncJobs(1),
    operationsManagement.loadDaikinSources().then(sources => {
      if (sources.length === 1) syncSourceId.value = sources[0]!.sourceId
    }),
  ]).catch(() => undefined)
}

onMounted(() => {
  if (administrator.value) {
    generalManagement.pendingQuery.value.profileCode = protocolScope.value
    generalManagement.pendingQuery.value.sourceScope = sourceScope.value
  }
  void listManagement.value.loadPendingDevices().catch(() => undefined)
  if (showDaikinSyncBar.value) {
    void loadDaikinSyncMeta()
  }
})
</script>

<template>
  <section class="pending-page">
    <header class="page-heading">
      <div class="heading-title">
        <h1>{{ t('deviceOnboarding.pending.title') }}</h1>
        <div v-if="administrator && protocolScope" class="scope-bar">
          <ElTag>{{ t('deviceOnboarding.labels.protocolScope') }} {{ protocolScope }}</ElTag>
          <ElButton link @click="clearProtocolScope">{{ t('deviceOnboarding.actions.showAll') }}</ElButton>
          <ElButton v-if="route.query.draftId" link @click="returnToDraft">{{ t('deviceOnboarding.actions.returnToDraft') }}</ElButton>
        </div>
      </div>

      <div v-if="showDaikinSyncBar" class="sync-toolbar">
        <span v-if="latestSyncSummary" class="sync-pill" :class="{ 'is-warning': Boolean(latestSyncSummary.errorCode) }">
          <span class="sync-dot" aria-hidden="true" />
          <span>{{ syncJobStatusText(latestSyncSummary.status) }}</span>
          <span v-if="latestSyncSummary.completedAt || latestSyncSummary.createdAt">{{ formatDateTime(latestSyncSummary.completedAt ?? latestSyncSummary.createdAt) }}</span>
        </span>
        <ElSelect
          v-if="operationsManagement.daikinSources.value.length > 1"
          v-model="syncSourceId"
          class="sync-source-select"
          :loading="operationsManagement.daikinSourcesLoading.value"
          :placeholder="t('deviceOnboarding.labels.syncSourcePlaceholder')"
        >
          <ElOption v-for="source in operationsManagement.daikinSources.value" :key="source.sourceId" :label="source.sourceName" :value="source.sourceId" />
        </ElSelect>
        <ElButton
          :disabled="!syncSourceId"
          :loading="operationsManagement.running.value.has(`daikin:sync:${syncSourceId}`)"
          @click="requestSync"
        >
          {{ t('deviceOnboarding.actions.syncDirectory') }}
        </ElButton>
        <ElButton
          v-if="operationsManagement.syncJob.value"
          :icon="RefreshCw"
          :loading="operationsManagement.running.value.has(`daikin:sync:status:${operationsManagement.syncJob.value.jobId}`)"
          @click="refreshSync"
        >
          {{ t('deviceOnboarding.actions.refreshSync') }}
        </ElButton>
        <ElButton link @click="openSyncHistory">{{ t('deviceOnboarding.actions.viewSyncHistory') }}</ElButton>
      </div>
    </header>

    <ElAlert v-if="listManagement.pendingError.value" :title="listManagement.pendingError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="operationMessage" :title="operationMessage" type="error" show-icon :closable="false" />
    <ElAlert v-if="detailManagement.productsError.value" :title="detailManagement.productsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="detailManagement.namingRulesError.value" :title="detailManagement.namingRulesError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="detailManagement.numericSourcesError.value" :title="detailManagement.numericSourcesError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="bindingContractError" :title="bindingContractError" type="error" show-icon :closable="false" />
    <ElAlert v-if="sensitiveError" :title="sensitiveError" type="error" show-icon :closable="false" />
    <ElAlert v-if="showDaikinSyncBar && operationsManagement.daikinSourcesError.value" :title="operationsManagement.daikinSourcesError.value.message" type="error" show-icon :closable="false" />

    <ElDrawer v-model="syncHistoryOpen" size="min(900px, 94vw)" :title="t('deviceOnboarding.pending.syncHistory')">
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
      <div class="filter-bar">
        <div v-if="administrator" class="source-segmented" role="group" :aria-label="t('deviceOnboarding.labels.sourceScope')">
          <button
            v-for="item in ([
              { value: 'ALL', label: t('deviceOnboarding.labels.sourceFilterAll') },
              { value: 'STANDARD', label: t('deviceOnboarding.labels.sourceFilterStandard') },
              { value: 'DAIKIN', label: t('deviceOnboarding.labels.sourceFilterDaikin') },
            ] as const)"
            :key="item.value"
            type="button"
            class="segment-button"
            :class="{ 'is-active': sourceScope === item.value }"
            @click="selectSourceScope(item.value)"
          >
            {{ item.label }}
          </button>
        </div>

        <ElSelect v-model="listManagement.pendingQuery.value.status" clearable :placeholder="t('deviceOnboarding.labels.status')" @change="query">
          <ElOption value="DISCOVERED" :label="t('deviceOnboarding.status.discovered')" />
          <ElOption value="IGNORED" :label="t('deviceOnboarding.status.ignored')" />
          <ElOption value="BOUND" :label="t('deviceOnboarding.status.bound')" />
        </ElSelect>
        <ElInput v-if="administrator" v-model="generalManagement.pendingQuery.value.identity" :placeholder="t('deviceOnboarding.labels.identity')" clearable @keyup.enter="query"><template #prefix><Search aria-hidden="true" /></template></ElInput>
        <ElInput v-if="administrator && !protocolScope" v-model="generalManagement.pendingQuery.value.profileCode" :placeholder="t('deviceOnboarding.labels.expectedProfile')" clearable @keyup.enter="query" />
        <ElButton :icon="Search" @click="query">{{ t('deviceOnboarding.actions.query') }}</ElButton>
        <ElButton :icon="RefreshCw" @click="resetFilters">{{ t('deviceOnboarding.actions.reset') }}</ElButton>

        <div v-if="showDaikinBatch" class="batch-actions">
          <ElButton type="primary" :disabled="!batchBindingReady || activationBusy" @click="openBatchBinding">{{ t('deviceOnboarding.actions.batchBinding') }}</ElButton>
          <ElButton type="primary" plain :disabled="!batchTemperatureReady || activationBusy" @click="openTemperatureBatch">{{ t('deviceOnboarding.actions.configureTemperature') }}</ElButton>
          <ElButton :disabled="!batchActivationReady || activationBusy" @click="openBatchActivation">{{ t('deviceOnboarding.actions.batchActivateIdentity') }}</ElButton>
        </div>
      </div>

      <ElSkeleton v-if="listManagement.pendingLoading.value && !listManagement.pendingDevices.value.items.length" animated :rows="5" />
      <ElTable v-else :data="listManagement.pendingDevices.value.items" row-key="pendingId" @selection-change="changeSelection">
        <ElTableColumn v-if="showDaikinBatch" type="selection" width="48" :selectable="selectableForBatch" />
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="maskedIdentityValue" min-width="190" />
        <ElTableColumn :label="t('deviceOnboarding.labels.expectedProfile')" min-width="140"><template #default="{ row }">{{ rowProfileLabel(asPending(row)) }}</template></ElTableColumn>
        <ElTableColumn v-if="showDaikinColumns" :label="t('deviceOnboarding.labels.roomCode')" min-width="105"><template #default="{ row }">{{ row.location?.roomCode || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn v-if="showDaikinColumns" :label="t('deviceOnboarding.labels.monitorAddress')" min-width="105"><template #default="{ row }">{{ row.location?.monitorAddress || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn v-if="showDaikinColumns" :label="t('deviceOnboarding.labels.assetReferenceCode')" min-width="140"><template #default="{ row }">{{ row.location?.assetReferenceCode || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.reportCount')" min-width="100"><template #default="{ row }">{{ formatNumber(row.reportCount) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.lastSeen')" min-width="170"><template #default="{ row }">{{ formatDateTime(row.lastSeenTime) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" min-width="95"><template #default="{ row }"><PendingStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.identityStatus')" min-width="115" fixed="right"><template #default="{ row }"><ElTag :type="row.identityStatus === 'ACTIVE' ? 'success' : row.identityStatus === 'INACTIVE' ? 'warning' : row.identityStatus === 'INVALID' ? 'danger' : 'info'">{{ t(`deviceOnboarding.identityStatus.${row.identityStatus || 'UNBOUND'}`) }}</ElTag></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.actions.viewDetail')" min-width="240" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <ElButton :icon="Eye" link @click="openDetail(asPending(row))">{{ t('deviceOnboarding.actions.viewDetail') }}</ElButton>
              <ElPopconfirm v-if="can(row, 'IGNORE')" :title="t('deviceOnboarding.messages.ignoreConfirm')" :confirm-button-text="t('deviceOnboarding.actions.ignore')" :cancel-button-text="t('deviceOnboarding.actions.cancel')" @confirm="ignore(asPending(row))"><template #reference><ElButton link type="warning">{{ t('deviceOnboarding.actions.ignore') }}</ElButton></template></ElPopconfirm>
              <ElButton v-if="can(row, 'RESTORE')" link @click="restore(asPending(row))">{{ t('deviceOnboarding.actions.restore') }}</ElButton>
              <ElButton v-if="can(row, 'BIND')" :icon="Settings2" link type="primary" @click="openBindingFor(asPending(row))">{{ t('deviceOnboarding.actions.prepareBinding') }}</ElButton>
            </div>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty :description="t('deviceOnboarding.empty.pending')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="listManagement.pendingDevices.value.page" :page-size="listManagement.pendingDevices.value.size" :total="listManagement.pendingDevices.value.total" @current-change="page => listManagement.setPendingQuery({ page }, false)" /></div>
    </ElCard>

    <ElDrawer :model-value="detailOpen" size="min(720px, 94vw)" :title="t('deviceOnboarding.pending.detail')" @update:model-value="detailOpen = false">
      <ElSkeleton v-if="detailManagement.pendingDetailLoading.value" animated :rows="8" />
      <ElAlert v-else-if="detailManagement.pendingDetailError.value" :title="detailManagement.pendingDetailError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="selectedPending">
        <div class="drawer-heading">
          <div>
            <h2>{{ isDetailDaikin ? (operationsManagement.selectedDirectory.value?.deviceName || t('deviceOnboarding.pending.daikinDevice')) : selectedPending.identityValue }}</h2>
            <p>{{ isDetailDaikin ? profileText(selectedPending.profileCode) : selectedPending.profileCode }}</p>
          </div>
          <PendingStatusTag :status="selectedPending.status" />
        </div>

        <ElDescriptions :column="2" border>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.identityType')">{{ identityTypeText(selectedPending.identityType) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.profileVersion')">{{ formatNumber(selectedPending.lastProfileVersion) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.reportCount')">{{ formatNumber(selectedPending.reportCount) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.firstSeen')">{{ formatDateTime(selectedPending.firstSeenTime) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.lastSeen')">{{ formatDateTime(selectedPending.lastSeenTime) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('deviceOnboarding.labels.sampleTruncated')">{{ selectedPending.sampleTruncated ? t('deviceOnboarding.labels.enabled') : t('common.missing') }}</ElDescriptionsItem>
        </ElDescriptions>

        <section class="sample-section">
          <h3>{{ t('deviceOnboarding.pending.connection') }}</h3>
          <ElSkeleton v-if="detailManagement.pendingConnectionLoading.value" animated :rows="3" />
          <ElAlert v-else-if="detailManagement.pendingConnectionError.value" :title="detailManagement.pendingConnectionError.value.message" type="error" show-icon :closable="false" />
          <ElDescriptions v-else-if="detailManagement.pendingConnection.value" :column="2" border>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.identityStatus')">
              <ElTag :type="detailManagement.pendingConnection.value.identityStatus === 'ACTIVE' ? 'success' : 'info'">{{ t(`deviceOnboarding.identityStatus.${detailManagement.pendingConnection.value.identityStatus}`) }}</ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.configEffective')">
              <ElTag :type="detailManagement.pendingConnection.value.configEffective ? 'success' : 'warning'">
                {{ detailManagement.pendingConnection.value.configEffective ? t('deviceOnboarding.labels.cacheEffective') : t('deviceOnboarding.labels.cachePending') }}
              </ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.equipmentId')">{{ detailManagement.pendingConnection.value.equipmentId || t('common.missing') }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.productId')">{{ detailManagement.pendingConnection.value.productId || t('common.missing') }}</ElDescriptionsItem>
          </ElDescriptions>
        </section>

        <section v-if="isDetailDaikin && operationsManagement.selectedDirectory.value" class="sample-section">
          <h3>{{ t('deviceOnboarding.pending.daikinDirectory') }}</h3>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.daikinKind')">{{ t(`deviceOnboarding.daikinKind.${operationsManagement.selectedDirectory.value.kind}`) }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.siteName')">{{ operationsManagement.selectedDirectory.value.siteName || operationsManagement.selectedDirectory.value.siteId }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.deviceName')">{{ operationsManagement.selectedDirectory.value.deviceName || operationsManagement.selectedDirectory.value.unitId }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.roomCode')">{{ operationsManagement.selectedDirectory.value.location?.roomCode || t('common.missing') }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.monitorAddress')">{{ operationsManagement.selectedDirectory.value.location?.monitorAddress || t('common.missing') }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.assetReferenceCode')">{{ operationsManagement.selectedDirectory.value.location?.assetReferenceCode || t('common.missing') }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.observedAt')">{{ formatDateTime(operationsManagement.selectedDirectory.value.observedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.directoryState')"><ElTag :type="operationsManagement.selectedDirectory.value.missing ? 'warning' : 'success'">{{ t(operationsManagement.selectedDirectory.value.missing ? 'deviceOnboarding.directory.missing' : 'deviceOnboarding.directory.present') }}</ElTag></ElDescriptionsItem>
          </ElDescriptions>
        </section>

        <details class="technical-details">
          <summary>{{ t('deviceOnboarding.pending.latestSample') }}</summary>
          <ElDescriptions :column="1" border class="technical-list">
            <ElDescriptionsItem :label="t('deviceOnboarding.labels.identity')"><CopyableValue :value="selectedPending.identityValue" /></ElDescriptionsItem>
            <ElDescriptionsItem v-if="isDetailDaikin && operationsManagement.selectedDirectory.value" :label="t('deviceOnboarding.labels.syncSourceId')"><CopyableValue :value="operationsManagement.selectedDirectory.value.sourceId" /></ElDescriptionsItem>
            <ElDescriptionsItem v-if="isDetailDaikin && operationsManagement.selectedDirectory.value" :label="t('deviceOnboarding.labels.controllerId')"><CopyableValue :value="operationsManagement.selectedDirectory.value.controllerId" /></ElDescriptionsItem>
          </ElDescriptions>
          <pre>{{ latestMetrics }}</pre>
        </details>

        <div class="drawer-actions">
          <ElPopconfirm v-if="can(selectedPending, 'IGNORE')" :title="t('deviceOnboarding.messages.ignoreConfirm')" :confirm-button-text="t('deviceOnboarding.actions.ignore')" :cancel-button-text="t('deviceOnboarding.actions.cancel')" @confirm="ignore(selectedPending)"><template #reference><ElButton type="warning">{{ t('deviceOnboarding.actions.ignore') }}</ElButton></template></ElPopconfirm>
          <ElButton v-if="can(selectedPending, 'RESTORE')" @click="restore(selectedPending)">{{ t('deviceOnboarding.actions.restore') }}</ElButton>
          <ElButton v-if="can(selectedPending, 'BIND')" type="primary" :icon="Settings2" @click="openBinding()">{{ t('deviceOnboarding.actions.prepareBinding') }}</ElButton>
          <ElButton
            v-if="detailManagement.pendingConnection.value?.identityStatus === 'ACTIVE' && !detailManagement.pendingConnection.value.configEffective"
            type="warning"
            :loading="detailManagement.running.value.has(`pending:refresh-effective:${selectedPending.pendingId}`)"
            @click="retryEffective(selectedPending.pendingId)"
          >
            {{ t('deviceOnboarding.actions.retryEffective') }}
          </ElButton>
          <ElButton v-if="detailManagement.pendingConnection.value?.identityId && detailManagement.pendingConnection.value.identityStatus !== 'ACTIVE'" type="primary" :loading="identityChangeSubmitting('ACTIVATE_DEVICE_IDENTITY', detailManagement.pendingConnection.value.identityId)" @click="submitIdentityChange('ACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.activateIdentity') }}</ElButton>
          <ElButton v-if="detailManagement.pendingConnection.value?.identityId && detailManagement.pendingConnection.value.identityStatus === 'ACTIVE'" type="warning" :loading="identityChangeSubmitting('DEACTIVATE_DEVICE_IDENTITY', detailManagement.pendingConnection.value.identityId)" @click="submitIdentityChange('DEACTIVATE_DEVICE_IDENTITY')">{{ t('deviceOnboarding.actions.deactivateIdentity') }}</ElButton>
          <ElButton v-if="detailManagement.pendingConnection.value?.equipmentId" @click="openReadings">{{ t('deviceOnboarding.actions.viewReadings') }}</ElButton>
        </div>

        <ElAlert v-if="bindingSubmitError" :title="bindingSubmitError" type="error" show-icon :closable="false" />
        <ChangeRequestControl business-view :change="sensitiveChange.current.value" :busy="sensitiveChange.pending.value.size > 0" @lookup="id => afterApprovalAction(() => sensitiveChange.load(id))" @submit="id => afterApprovalAction(() => sensitiveChange.submit(id))" @withdraw="id => afterApprovalAction(() => sensitiveChange.withdraw(id))" @approve="(id, comment) => afterApprovalAction(() => sensitiveChange.approve(id, comment))" @reject="(id, comment) => afterApprovalAction(() => sensitiveChange.reject(id, comment))" @execute="id => afterApprovalAction(() => sensitiveChange.execute(id), true)" />
      </template>
    </ElDrawer>

    <BindingDraftDialog
      :open="bindingOpen"
      :pending="selectedPending"
      :batch-rows="batchRows"
      :products="detailManagement.products.value.items"
      :product="bindingProduct"
      :product-loading="detailManagement.productsLoading.value || detailManagement.productDetailLoading.value"
      :product-total="detailManagement.products.value.total"
      :product-page="detailManagement.products.value.page"
      :product-size="detailManagement.products.value.size"
      :naming-rules="generalManagement.namingRules.value"
      :naming-rules-loading="generalManagement.namingRulesLoading.value"
      :numeric-sources="operationsManagement.numericSources.value"
      :numeric-sources-loading="operationsManagement.numericSourcesLoading.value"
      :temperature-options="isDetailDaikin ? operationsManagement.temperatureOptions.value : null"
      :temperature-options-loading="isDetailDaikin && operationsManagement.temperatureOptionsLoading.value"
      :temperature-plans="isDetailDaikin ? operationsManagement.temperaturePlans.value : []"
      :temperature-previewing="isDetailDaikin && operationsManagement.temperaturePreviewLoading.value"
      :temperature-preview-error="isDetailDaikin ? operationsManagement.temperaturePreviewError.value?.message : null"
      :binding-options="isDetailDaikin ? operationsManagement.bindingOptions.value : null"
      :submitting="bindingSubmitting"
      :submit-error="bindingSubmitError"
      :automatic-mapping-rejected="automaticMappingRejected"
      :allow-empty-points="isDetailDaikin"
      :product-search-enabled="!isDetailDaikin"
      @close="bindingOpen = false"
      @product-change="selectProduct"
      @product-search="searchBindingProducts"
      @product-page-change="changeBindingProductPage"
      @binding-scope-change="(spaceId, systemGroupId) => loadBindingEquipment(1, spaceId, systemGroupId)"
      @equipment-page-change="loadBindingEquipment"
      @temperature-preview="previewBindingTemperature"
      @temperature-preview-invalidated="operationsManagement.clearTemperaturePlans()"
      @submit="submitBindingDraft"
    />

    <TemperatureBatchDialog
      :open="temperatureBatchOpen"
      :rows="temperatureBatchRows"
      :options="operationsManagement.temperatureOptions.value"
      :options-loading="operationsManagement.temperatureOptionsLoading.value"
      :options-error="operationsManagement.temperatureOptionsError.value?.message"
      :plans="operationsManagement.temperaturePlans.value"
      :previewing="operationsManagement.temperaturePreviewLoading.value"
      :preview-error="operationsManagement.temperaturePreviewError.value?.message"
      :submitting="operationsManagement.running.value.has('temperature:batch:create')"
      :initialization-preview="operationsManagement.temperatureInitializationPreview.value"
      :initialization-previewing="operationsManagement.temperatureInitializationPreviewLoading.value"
      :initialization-preview-error="operationsManagement.temperatureInitializationPreviewError.value?.message"
      :initialization-submitting="operationsManagement.running.value.has('temperature:initialize:create')"
      :initialization-submit-error="operationsManagement.temperatureInitializationJobError.value?.message"
      :job="operationsManagement.temperatureBatchJob.value"
      :job-loading="operationsManagement.temperatureBatchJobLoading.value"
      :job-error="operationsManagement.temperatureBatchJobError.value?.message"
      :platform-admin="administrator"
      :rule-submitting="operationsManagement.temperatureRuleRequestLoading.value"
      :rule-error="operationsManagement.temperatureRuleRequestError.value?.message"
      :rule-result="operationsManagement.temperatureRuleRequest.value"
      @close="temperatureBatchOpen = false"
      @preview="previewTemperatureBatch"
      @preview-invalidated="clearTemperaturePreviews"
      @submit="submitTemperatureBatch"
      @initialization-preview="previewTemperatureInitialization"
      @initialization-submit="submitTemperatureInitialization"
      @refresh-job="refreshTemperatureBatch"
      @retry-job="retryTemperatureBatch"
      @new-task="restartTemperatureBatch"
      @rule-submit="submitTemperatureRule"
    />

    <ElDialog v-model="batchResultsOpen" :title="t('deviceOnboarding.pending.bindingResults')" width="min(760px, 94vw)">
      <ElTable :data="operationsManagement.bindingApplications.value" row-key="pendingId">
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="pendingId" min-width="180" />
        <ElTableColumn :label="t('deviceOnboarding.labels.requestId')" min-width="180"><template #default="{ row }">{{ asBindingApplication(row).requestId || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" prop="status" min-width="130" />
        <ElTableColumn :label="t('deviceOnboarding.labels.errorCode')" min-width="160"><template #default="{ row }">{{ asBindingApplication(row).errorCode || t('common.missing') }}</template></ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="activationOpen" :title="t('deviceOnboarding.actions.batchActivateIdentity')" :close-on-click-modal="!activationBusy" :close-on-press-escape="!activationBusy" :show-close="!activationBusy" width="min(620px, 94vw)">
      <p>{{ t('deviceOnboarding.messages.batchActivationConfirm') }}{{ activationRows.length }}</p>
      <ul class="activation-preview"><li v-for="row in activationRows" :key="row.pendingId">{{ t('deviceOnboarding.messages.batchActivationPreviewRow', { device: activationLabel(row) }) }}</li></ul>
      <template #footer><ElButton :disabled="activationBusy" @click="activationOpen = false">{{ t('deviceOnboarding.actions.cancel') }}</ElButton><ElButton type="primary" :loading="activationBusy" @click="submitBatchActivation">{{ t('deviceOnboarding.actions.batchActivateIdentity') }}</ElButton></template>
    </ElDialog>
    <ElDialog v-model="activationResultsOpen" :title="t('deviceOnboarding.pending.activationResults')" width="min(760px, 94vw)">
      <ElTable :data="activationResults" row-key="pendingId">
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" min-width="160"><template #default="{ row }">{{ row.label }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.status')" min-width="180"><template #default="{ row }"><ElTag :type="row.success ? 'success' : 'warning'">{{ row.result }}</ElTag></template></ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="readingsOpen" :title="t('deviceOnboarding.pending.readings')" width="min(900px, 94vw)" @closed="equipmentReadings.clear()">
      <ElSkeleton v-if="equipmentReadings.loading.value" animated :rows="5" />
      <ElAlert v-else-if="equipmentReadings.error.value" :title="equipmentReadings.error.value" type="error" show-icon :closable="false" />
      <ElTable v-else-if="equipmentReadings.readings.value" :data="equipmentReadings.readings.value.points" row-key="pointId">
        <ElTableColumn :label="t('deviceOnboarding.labels.readingPointName')" prop="pointName" min-width="150" />
        <ElTableColumn :label="t('deviceOnboarding.labels.readingPointCode')" prop="pointCode" min-width="140" />
        <ElTableColumn :label="t('deviceOnboarding.labels.readingValue')" min-width="120"><template #default="{ row }">{{ row.value == null ? t('common.missing') : `${formatNumber(row.value)} ${row.unit === '1' ? '' : (row.unit || '')}` }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.readingStatus')" min-width="130"><template #default="{ row }">{{ readingStatusText(row.status) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.eventTime')" min-width="180"><template #default="{ row }">{{ row.eventTime ? formatDateTime(row.eventTime) : t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.reason')" min-width="240"><template #default="{ row }">{{ readingReasonText(row.reason) }}</template></ElTableColumn>
      </ElTable>
    </ElDialog>
  </section>
</template>

<style scoped>
.pending-page { display: grid; gap: var(--bec-space-group); min-width: 0; }
.page-heading, .heading-title, .scope-bar, .sync-toolbar, .drawer-heading, .filter-bar, .batch-actions, .row-actions, .drawer-actions { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.page-heading, .drawer-heading { justify-content: space-between; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.sync-pill { display: inline-flex; align-items: center; gap: var(--bec-space-tight); padding: var(--bec-ref-space-4) var(--bec-ref-space-12); border-radius: var(--bec-radius-tag); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.sync-dot { width: var(--bec-space-tight); height: var(--bec-space-tight); border-radius: var(--bec-radius-tag); background: var(--bec-color-success); }
.sync-pill.is-warning .sync-dot { background: var(--bec-color-warning); }
.sync-source-select { width: calc(var(--bec-navigation-width) * 0.85); }
.source-segmented { display: inline-flex; align-items: center; padding: var(--bec-ref-space-4); border-radius: var(--bec-radius-card); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); }
.segment-button { border: none; background: transparent; color: var(--bec-color-text-secondary); padding: var(--bec-ref-space-4) var(--bec-ref-space-12); border-radius: var(--bec-radius-control); font-size: var(--bec-font-size-small); cursor: pointer; transition: background-color 0.15s ease, color 0.15s ease; }
.segment-button.is-active { background: var(--bec-color-surface); color: var(--bec-color-action-primary); font-weight: var(--bec-font-weight-heading); box-shadow: var(--bec-shadow-card); }
.filter-bar { margin-bottom: var(--bec-space-group); gap: var(--bec-space-tight); }
.filter-bar > :deep(.el-input) { flex: 1 1 var(--bec-navigation-width); max-width: calc(var(--bec-navigation-width) * 1.2); min-width: calc(var(--bec-navigation-width) * 0.75); }
.filter-bar :deep(.el-input__prefix svg) { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.filter-bar > :deep(.el-select) { width: calc(var(--bec-navigation-width) * 0.7); min-width: calc(var(--bec-navigation-width) * 0.55); }
.batch-actions { margin-left: auto; }
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.drawer-actions { justify-content: flex-end; margin-top: var(--bec-space-section); gap: var(--bec-space-tight); }
.row-actions { gap: var(--bec-space-tight); }
.sample-section { display: grid; gap: var(--bec-space-tight); margin-top: var(--bec-space-section); }
.technical-details { margin-top: var(--bec-space-section); padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); color: var(--bec-color-text-secondary); }
.technical-details summary { width: fit-content; cursor: pointer; color: var(--bec-color-text-secondary); font-weight: var(--bec-font-weight-heading); }
.technical-list { margin-top: var(--bec-space-tight); }
.sync-history-table { margin-top: var(--bec-space-group); }
.activation-preview { max-height: calc(var(--bec-ref-space-48) * 6); overflow: auto; }
pre { margin: var(--bec-space-tight) 0 0; padding: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); overflow: auto; }
@media (max-width: 768px) {
  .batch-actions { margin-left: 0; width: 100%; }
  .filter-bar > :deep(.el-input), .filter-bar > :deep(.el-select), .sync-source-select { flex: 1 1 100%; max-width: none; width: 100%; }
}
</style>
