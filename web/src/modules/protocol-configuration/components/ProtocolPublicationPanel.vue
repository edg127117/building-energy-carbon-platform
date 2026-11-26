<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ChangeRequestControl,
  getChangeRequest,
  newIdempotencyKey,
  useSensitiveChange,
  type SensitiveChange,
} from '@/modules/access-control/public'
import { listDeviceProducts, type DeviceProductListItem } from '@/modules/device-onboarding/public'
import {
  CopyableValue, ElAlert, ElButton, ElCard, ElCheckbox, ElCheckboxGroup, ElDescriptions, ElDescriptionsItem,
  ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect, ElSkeleton,
  ElTabPane, ElTable, ElTableColumn, ElTabs, ElTag, RefreshCw, Settings2,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import {
  freezeProtocolVersion,
  getProtocolDeploymentDetail,
  importProtocolVersions,
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  previewDraftProtocolPublication,
  previewProtocolPublication,
  previewProtocolRollback,
  registerProtocolPublicationTarget,
  requestDraftProtocolPublication,
  requestProtocolPublication,
  requestProtocolRollback,
} from '../api/protocol-configuration'
import {
  DRAFT_PREVIEW_VERSION_ID,
  parseProtocolExportPackage,
  type ParsedProtocolImport,
  type ProtocolDeployment,
  type ProtocolDeploymentDetail,
  type ProtocolFrozenVersion,
  type ProtocolOutputVersion,
  type ProtocolPublicationPreview,
  type ProtocolPublicationTarget,
} from '../models/protocol-configuration'

type OperationsTab = 'targets' | 'history' | 'migration'

const props = defineProps<{
  active?: boolean
  freezeReady?: boolean
  draftId: string | null
  draftRevision: number | null
  draftDirty?: boolean
  productEnabled: boolean
  selectedProduct: DeviceProductListItem | null
  profileCode?: string
}>()

const emit = defineEmits<{
  'open-pending-devices': []
  'fix-config': []
  'targets-loaded': [targets: ProtocolPublicationTarget[]]
}>()

const changes = useSensitiveChange()
const targets = ref<ProtocolPublicationTarget[]>([])
const versions = ref<ProtocolFrozenVersion[]>([])
const history = ref<ProtocolDeployment[]>([])
const targetId = ref('')
const versionIds = ref<string[]>([])

const draftPreview = ref<ProtocolPublicationPreview | null>(null)
const draftPreviewLoading = ref(false)
const draftPreviewError = ref<string | null>(null)

const confirmation = ref<ProtocolPublicationPreview | null>(null)
const detail = ref<ProtocolDeploymentDetail | null>(null)
const confirmOpen = ref(false)
const detailOpen = ref(false)
const rollbackSequence = ref<number | null>(null)

const operationsOpen = ref(false)
const operationsTab = ref<OperationsTab>('targets')
const registerOpen = ref(false)
const targetName = ref('')
const outputVersion = ref<ProtocolOutputVersion>('V1')
const allowedTopicsText = ref('')
const oneTimeKey = ref<string | null>(null)

const importOpen = ref(false)
const importPayload = ref('')
const importPackage = ref<ParsedProtocolImport | null>(null)
const productBindings = ref<Record<string, string>>({})
const productOptions = ref<DeviceProductListItem[]>([])
const productPage = ref({ page: 1, size: 20, total: 0 })
const productKeyword = ref('')
const productsLoading = ref(false)

const loading = ref(false)
const actionBusy = ref(false)
const error = ref<string | null>(null)
const lastRefresh = ref<number | null>(null)
const pollExpired = ref(false)

let draftPreviewGeneration = 0
let confirmationGeneration = 0
let detailGeneration = 0
let historyGeneration = 0
let refreshGeneration = 0
let pollStarted = 0
let pollKey = ''
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
// 同一 (draftId, revision, targetId, expectedSequence) 复用同一幂等键，配合后端 B3 先查已有申请防重与序号推进重试。
const publishKeyByTuple = new Map<string, string>()

const selectedTarget = computed(() => targets.value.find(item => item.targetId === targetId.value) ?? null)
const targetSelectable = computed(() => selectedTarget.value !== null && selectedTarget.value.status !== 'UNKNOWN')
const approvalBusy = computed(() => changes.pending.value.size > 0)
const draftReadyForPublish = computed(() => Boolean(
  props.draftId
  && props.draftRevision
  && props.productEnabled
  && !props.draftDirty,
))
const freezeAllowed = computed(() => Boolean(props.freezeReady !== false && draftReadyForPublish.value))
const canSubmitDraftPublish = computed(() => Boolean(
  draftReadyForPublish.value
  && targetSelectable.value
  && draftPreview.value
  && draftPreview.value.targetId === targetId.value
  && draftPreview.value.expectedSequence === selectedTarget.value?.currentSequence
  && !draftPreviewLoading.value
  && !actionBusy.value,
))
const importProfiles = computed(() => importPackage.value?.profiles ?? [])
const importReady = computed(() => importProfiles.value.length > 0 && importProfiles.value.every(item => Boolean(productBindings.value[item.profileId])))

/** 仅按当前提交的审批单编号匹配本次加载回执，避免在未送审或仅选择产品时误报加载成功。 */
const currentApprovalDeployment = computed(() => {
  const approvalId = changes.current.value?.requestId
  if (!approvalId) return null
  return history.value.find(item => item.approvalId === approvalId) ?? null
})
const currentRequestLoaded = computed(() => Boolean(
  !props.draftDirty
  && changes.current.value?.status === 'EXECUTED'
  && currentApprovalDeployment.value?.status === 'LOADED',
))
const hasActiveApprovalTracking = computed(() => {
  const current = changes.current.value
  if (!current?.requestId) return false
  if (current.status === 'PENDING_REVIEW' || current.status === 'APPROVED') return true
  if (current.status === 'EXECUTED') {
    const deployment = currentApprovalDeployment.value
    return !deployment || (deployment.status !== 'LOADED' && deployment.status !== 'FAILED')
  }
  return false
})

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

function schedulePolling() {
  stopPolling()
  const target = selectedTarget.value
  const shouldPoll = hasActiveApprovalTracking.value || target?.status === 'PENDING_SYNC'
  if (disposed || props.active === false || document.hidden || !shouldPoll) return
  const key = [
    changes.current.value?.requestId ?? '',
    changes.current.value?.status ?? '',
    target?.targetId ?? '',
    target?.currentSequence ?? '',
    currentApprovalDeployment.value?.status ?? '',
  ].join(':')
  if (key !== pollKey) {
    pollKey = key
    pollStarted = Date.now()
    pollExpired.value = false
  }
  const reviewInProgress = changes.current.value?.status === 'PENDING_REVIEW'
    || changes.current.value?.status === 'APPROVED'
  // 两分钟只用于等待适配器回执，独立审核完成时间由审核人决定。
  if (!reviewInProgress && Date.now() - pollStarted >= 120000) {
    pollExpired.value = true
    return
  }
  pollTimer = setTimeout(() => { void refresh().catch(stopPolling) }, 5000)
}

function visibilityChanged() {
  if (props.active === false || document.hidden) stopPolling()
  else void refresh().catch(stopPolling)
}

onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  void refresh().catch(() => undefined)
})

onBeforeUnmount(() => {
  disposed = true
  ++refreshGeneration
  ++historyGeneration
  ++draftPreviewGeneration
  ++confirmationGeneration
  ++detailGeneration
  stopPolling()
  document.removeEventListener('visibilitychange', visibilityChanged)
})

watch(() => props.active, active => {
  if (active === false) {
    stopPolling()
    return
  }
  void refresh().catch(stopPolling)
})

watch([() => props.draftId, () => props.draftRevision, targetId], () => {
  changes.current.value = null
  stopPolling()
})

watch(
  () => [props.active, props.draftId, props.draftRevision, props.draftDirty, props.productEnabled, targetId.value, selectedTarget.value?.currentSequence, selectedTarget.value?.status],
  () => {
    draftPreview.value = null
    draftPreviewError.value = null
    ++draftPreviewGeneration
    if (props.active !== false && draftReadyForPublish.value && targetSelectable.value) {
      void loadDraftPreview()
    }
  },
)

watch([targetId, versionIds], () => {
  confirmation.value = null
  confirmOpen.value = false
  ++confirmationGeneration
}, { deep: true })

watch(importPayload, () => {
  importPackage.value = null
  productBindings.value = {}
})

function publicationError(reason: unknown): string {
  const code = requestErrorCode(reason)
  if (code) {
    if (code === 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR') return t('protocolConfiguration.publication.validation.ambiguous')
    if (code === 'PROTOCOL_VALIDATION_FAILED' || code.startsWith('PROTOCOL_SNAPSHOT_')) return t('protocolConfiguration.publication.validation.invalidConfiguration')
  }
  return requestErrorMessage(reason)
}

async function loadDraftPreview() {
  const target = selectedTarget.value
  if (!target || target.status === 'UNKNOWN' || !draftReadyForPublish.value || !props.draftId || !props.draftRevision) return
  const owner = ++draftPreviewGeneration
  const requestedDraftId = props.draftId
  const requestedRevision = props.draftRevision
  const requestedTargetId = target.targetId
  const requestedSequence = target.currentSequence
  draftPreviewLoading.value = true
  draftPreviewError.value = null
  try {
    const result = await previewDraftProtocolPublication({
      draftId: requestedDraftId,
      revision: requestedRevision,
      targetId: requestedTargetId,
      expectedSequence: requestedSequence,
    })
    if (
      owner !== draftPreviewGeneration
      || props.draftId !== requestedDraftId
      || props.draftRevision !== requestedRevision
      || targetId.value !== requestedTargetId
      || selectedTarget.value?.currentSequence !== requestedSequence
    ) return
    draftPreview.value = result
  } catch (reason) {
    if (owner === draftPreviewGeneration) {
      draftPreview.value = null
      draftPreviewError.value = publicationError(reason)
    }
  } finally {
    if (owner === draftPreviewGeneration) draftPreviewLoading.value = false
  }
}

async function submitDraftPublish() {
  const target = selectedTarget.value
  if (!canSubmitDraftPublish.value || !target || !props.draftId || !props.draftRevision) return
  const draftId = props.draftId
  const revision = props.draftRevision
  const tupleKey = `${draftId}:${revision}:${target.targetId}:${target.currentSequence}`
  let idempotencyKey = publishKeyByTuple.get(tupleKey)
  if (!idempotencyKey) {
    idempotencyKey = newIdempotencyKey()
    publishKeyByTuple.set(tupleKey, idempotencyKey)
  }
  actionBusy.value = true
  error.value = null
  try {
    const change = await requestDraftProtocolPublication({
      draftId,
      revision,
      targetId: target.targetId,
      expectedSequence: target.currentSequence,
      idempotencyKey,
    })
    if (disposed || props.draftId !== draftId || props.draftRevision !== revision || targetId.value !== target.targetId) return
    changes.current.value = change
    ElMessage.success(t('protocolConfiguration.publication.messages.publishSubmitted'))
    schedulePolling()
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

async function refresh() {
  const owner = ++refreshGeneration
  ++historyGeneration
  loading.value = true
  error.value = null
  const trackedChange = changes.current.value
  try {
    const [nextTargets, nextVersions, refreshedChange] = await Promise.all([
      listProtocolPublicationTargets(),
      listProtocolVersions(),
      trackedChange ? getChangeRequest(trackedChange.requestId).catch(() => undefined) : Promise.resolve(),
    ])
    if (owner !== refreshGeneration) return
    // 切换草稿、修订或目标后，已发出的轮询不能恢复旧申请。
    if (refreshedChange && changes.current.value === trackedChange) changes.current.value = refreshedChange
    targets.value = nextTargets
    emit('targets-loaded', nextTargets)
    versions.value = nextVersions
    lastRefresh.value = Date.now()
    if (confirmation.value && nextTargets.find(item => item.targetId === confirmation.value?.targetId)?.currentSequence !== confirmation.value.expectedSequence) {
      confirmation.value = null
      confirmOpen.value = false
      ++confirmationGeneration
    }
    if (targetId.value && !nextTargets.some(item => item.targetId === targetId.value)) {
      targetId.value = ''
      history.value = []
    }
    if (targetId.value) await loadHistory(targetId.value)
    else history.value = []
    if (props.active !== false && draftReadyForPublish.value && targetSelectable.value && !draftPreview.value && !draftPreviewLoading.value) {
      void loadDraftPreview()
    }
    schedulePolling()
  } catch (reason) {
    error.value = publicationError(reason)
    throw reason
  } finally {
    if (owner === refreshGeneration) loading.value = false
  }
}

async function selectTarget(value: string) {
  stopPolling()
  targetId.value = value
  detailOpen.value = false
  ++detailGeneration
  history.value = []
  ++historyGeneration
  if (!value) return
  try {
    await loadHistory(value)
    schedulePolling()
  } catch {
    stopPolling()
  }
}

async function loadHistory(value: string) {
  const owner = ++historyGeneration
  try {
    const result = await listProtocolDeploymentHistory(value)
    if (owner === historyGeneration && targetId.value === value) history.value = result
  } catch (reason) {
    if (owner === historyGeneration && targetId.value === value) error.value = publicationError(reason)
    throw reason
  }
}

async function inspectPublication(historicalSequence: number | null = null) {
  const target = selectedTarget.value
  if (!target || actionBusy.value) return
  const owner = ++confirmationGeneration
  const ids = [...versionIds.value]
  actionBusy.value = true
  error.value = null
  try {
    const result = historicalSequence === null
      ? await previewProtocolPublication({ targetId: target.targetId, expectedSequence: target.currentSequence, versionIds: ids })
      : await previewProtocolRollback({ targetId: target.targetId, expectedSequence: target.currentSequence, historicalSequence })
    if (owner !== confirmationGeneration || targetId.value !== result.targetId || selectedTarget.value?.currentSequence !== result.expectedSequence) return
    confirmation.value = result
    rollbackSequence.value = historicalSequence
    confirmOpen.value = true
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

async function confirmPublication() {
  const value = confirmation.value
  if (!value || value.targetId !== targetId.value || value.expectedSequence !== selectedTarget.value?.currentSequence) {
    confirmOpen.value = false
    return
  }
  confirmOpen.value = false
  if (rollbackSequence.value === null) await publishLegacy()
  else await rollback(value.targetId, rollbackSequence.value)
  confirmation.value = null
}

async function showDetail(sequence: number) {
  const owner = ++detailGeneration
  const selected = targetId.value
  detail.value = null
  try {
    const result = await getProtocolDeploymentDetail(selected, sequence)
    if (owner === detailGeneration && selected === targetId.value) {
      detail.value = result
      detailOpen.value = true
    }
  } catch (reason) {
    error.value = publicationError(reason)
  }
}

async function freezeCurrentDraft() {
  if (!freezeAllowed.value || !props.draftId || !props.draftRevision || actionBusy.value) return
  ++refreshGeneration
  loading.value = false
  actionBusy.value = true
  error.value = null
  try {
    const frozen = await freezeProtocolVersion(props.draftId, props.draftRevision)
    versions.value = await listProtocolVersions()
    if (!versionIds.value.includes(frozen.versionId)) versionIds.value = [...versionIds.value, frozen.versionId]
    ElMessage.success(t('protocolConfiguration.publication.messages.frozen'))
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

async function registerTarget() {
  const topics = allowedTopicsText.value.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
  if (!targetName.value.trim() || !topics.length) {
    error.value = t('protocolConfiguration.publication.validation.targetRequired')
    return
  }
  actionBusy.value = true
  error.value = null
  try {
    const result = await registerProtocolPublicationTarget({
      name: targetName.value.trim(),
      outputVersion: outputVersion.value,
      allowedTopics: topics,
    })
    oneTimeKey.value = result.oneTimeKey
    targets.value = [result.target, ...targets.value.filter(item => item.targetId !== result.target.targetId)]
    emit('targets-loaded', targets.value)
    targetId.value = result.target.targetId
    ++historyGeneration
    history.value = []
    registerOpen.value = false
    targetName.value = ''
    allowedTopicsText.value = ''
    try {
      targets.value = await listProtocolPublicationTargets()
      emit('targets-loaded', targets.value)
    } catch (reason) {
      error.value = publicationError(reason)
    }
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

function closeOneTimeKey() {
  oneTimeKey.value = null
}

function openOperationsDrawer(tab: OperationsTab = 'targets') {
  operationsTab.value = tab
  operationsOpen.value = true
}

async function loadEnabledProducts(page = 1, keyword = productKeyword.value) {
  productsLoading.value = true
  try {
    const result = await listDeviceProducts({ page, size: productPage.value.size, status: 'ENABLED', keyword: keyword.trim() || undefined })
    productPage.value = { page: result.page, size: result.size, total: result.total }
    const current = props.selectedProduct?.status === 'ENABLED' ? [props.selectedProduct] : []
    productOptions.value = [...new Map([...current, ...productOptions.value, ...result.items].map(item => [item.productId, item])).values()]
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    productsLoading.value = false
  }
}

function openImport() {
  importOpen.value = true
  void loadEnabledProducts()
}

function parseImportPayload() {
  error.value = null
  try {
    const value = parseProtocolExportPackage(importPayload.value)
    importPackage.value = value
    productBindings.value = Object.fromEntries(value.profiles.map(item => [item.profileId, '']))
  } catch {
    importPackage.value = null
    productBindings.value = {}
    error.value = t('protocolConfiguration.publication.validation.importPackage')
  }
}

async function importVersions() {
  const value = importPackage.value
  if (!value || !importReady.value) {
    error.value = t('protocolConfiguration.publication.validation.productBindings')
    return
  }
  actionBusy.value = true
  error.value = null
  try {
    const imported = await importProtocolVersions({
      snapshotJson: value.snapshotJson,
      archiveJson: value.archiveJson,
      productBindings: { ...productBindings.value },
    })
    versions.value = await listProtocolVersions()
    versionIds.value = [...new Set([...versionIds.value, ...imported.map(item => item.versionId)])]
    importPayload.value = ''
    importPackage.value = null
    productBindings.value = {}
    importOpen.value = false
    ElMessage.success(t('protocolConfiguration.publication.messages.imported'))
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

function searchProducts(keyword: string) {
  productKeyword.value = keyword
  void loadEnabledProducts(1, keyword)
}

async function publishLegacy() {
  const target = selectedTarget.value
  if (!target || target.status === 'UNKNOWN') {
    error.value = t('protocolConfiguration.publication.validation.targetUnavailable')
    return
  }
  if (!versionIds.value.length) {
    error.value = t('protocolConfiguration.publication.validation.versionRequired')
    return
  }
  await createApproval(
    () => requestProtocolPublication({
      targetId: target.targetId,
      expectedSequence: target.currentSequence,
      versionIds: [...versionIds.value],
      idempotencyKey: newIdempotencyKey(),
    }),
    t('protocolConfiguration.publication.messages.rollbackCreated'),
  )
}

async function rollback(deploymentTargetId: string, sequence: number) {
  const target = selectedTarget.value
  const deployment = history.value.find(item => item.targetId === deploymentTargetId && item.sequence === sequence)
  if (!target || !deployment || target.status === 'UNKNOWN' || deployment.targetId !== target.targetId || deployment.status !== 'LOADED') {
    error.value = t('protocolConfiguration.publication.validation.rollbackTarget')
    return
  }
  await createApproval(
    () => requestProtocolRollback({
      targetId: target.targetId,
      historicalSequence: deployment.sequence,
      expectedSequence: target.currentSequence,
      idempotencyKey: newIdempotencyKey(),
    }),
    t('protocolConfiguration.publication.messages.rollbackCreated'),
  )
  operationsOpen.value = false
}

async function createApproval(action: () => Promise<SensitiveChange>, successMessage: string) {
  actionBusy.value = true
  error.value = null
  try {
    changes.current.value = await action()
    ElMessage.success(successMessage)
  } catch (reason) {
    error.value = publicationError(reason)
  } finally {
    actionBusy.value = false
  }
}

async function afterApprovalAction(action: () => Promise<unknown>) {
  try {
    await action()
    await refresh()
  } catch {
    // 公共审批控件保留标准错误，发布面板仅刷新后端运行状态。
  }
}

function statusType(status: string) {
  if (status === 'LOADED' || status === 'READY') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PENDING_SYNC') return 'warning'
  return 'info'
}

function changeTagType(changeType: string) {
  if (changeType === 'ADDED') return 'success'
  if (changeType === 'REPLACED') return 'warning'
  if (changeType === 'REMOVED') return 'danger'
  return 'info'
}

function versionLabel(version: ProtocolFrozenVersion) {
  return t('protocolConfiguration.publication.versionLabel', {
    name: version.configuration.name,
    revision: version.draftRevision,
    digest: version.digest,
  })
}

function productPageLabel() {
  return t('protocolConfiguration.publication.productPage', {
    current: productPage.value.page,
    total: Math.max(1, Math.ceil(productPage.value.total / productPage.value.size)),
  })
}

defineExpose({ freezeCurrentDraft, openOperationsDrawer, refresh })
</script>

<template>
  <div class="publication-stage">
    <ElCard v-show="active !== false" shadow="never" class="publication-panel">
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>{{ t('protocolConfiguration.publication.title') }}</h2>
            <p>{{ t('protocolConfiguration.publication.description') }}</p>
          </div>
          <div class="actions">
            <span v-if="lastRefresh" class="meta-text">{{ `${t('protocolConfiguration.flow.lastRefresh')} ${formatDateTime(lastRefresh)}` }}</span>
            <ElButton :icon="RefreshCw" :loading="loading" @click="refresh">{{ t('protocolConfiguration.publication.actions.refresh') }}</ElButton>
            <ElButton :icon="Settings2" @click="openOperationsDrawer('history')">{{ t('protocolConfiguration.actions.operationsDrawer') }}</ElButton>
          </div>
        </div>
      </template>

      <ElAlert v-if="pollExpired" :title="t('protocolConfiguration.flow.pollExpired')" type="info" :closable="false" />
      <ElAlert v-if="error || changes.error.value" :title="error || changes.error.value || ''" type="error" show-icon :closable="false" />
      <ElAlert v-if="draftDirty" :title="t('protocolConfiguration.publication.draftDirtyHint')" type="warning" show-icon :closable="false" />
      <ElAlert v-else-if="!draftId || !draftRevision || !productEnabled" :title="t('protocolConfiguration.publication.draftUnsavedHint')" type="warning" show-icon :closable="false" />

      <div class="publication-grid">
        <section class="section-block">
          <div class="section-heading">
            <h3>{{ t('protocolConfiguration.publication.sections.target') }}</h3>
            <ElButton link type="primary" @click="registerOpen = true">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton>
          </div>

          <ElEmpty v-if="!loading && !targets.length" :description="t('protocolConfiguration.publication.empty.targets')">
            <ElButton type="primary" @click="registerOpen = true">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton>
          </ElEmpty>
          <template v-else>
            <ElSelect
              :model-value="targetId"
              clearable
              :placeholder="t('protocolConfiguration.publication.placeholders.target')"
              @change="selectTarget"
            >
              <ElOption
                v-for="target in targets"
                :key="target.targetId"
                :value="target.targetId"
                :disabled="target.status === 'UNKNOWN'"
                :label="`${target.name} · ${t(`protocolConfiguration.publication.status.${target.status}`)}`"
              />
            </ElSelect>
            <ElDescriptions v-if="selectedTarget" :column="2" border>
              <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.status')">
                <ElTag :type="statusType(selectedTarget.status)">{{ t(`protocolConfiguration.publication.status.${selectedTarget.status}`) }}</ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.sequence')">{{ selectedTarget.currentSequence }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.outputVersion')">{{ selectedTarget.outputVersion }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.lastSeen')">{{ selectedTarget.lastSeen ? formatDateTime(selectedTarget.lastSeen) : '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.topics')" :span="2">{{ selectedTarget.allowedTopics.join('、') }}</ElDescriptionsItem>
              <ElDescriptionsItem v-if="selectedTarget.errorCode" :label="t('protocolConfiguration.publication.labels.error')" :span="2">{{ selectedTarget.errorCode }}</ElDescriptionsItem>
            </ElDescriptions>
          </template>
        </section>

        <section class="section-block">
          <div class="section-heading">
            <h3>{{ t('protocolConfiguration.publication.sections.previewDiff') }}</h3>
            <ElButton
              v-if="targetSelectable && draftReadyForPublish"
              link
              :loading="draftPreviewLoading"
              @click="loadDraftPreview"
            >
              {{ t('protocolConfiguration.publication.actions.refreshPreview') }}
            </ElButton>
          </div>

          <ElSkeleton v-if="draftPreviewLoading" animated :rows="4" />
          <ElAlert v-else-if="draftPreviewError" :title="draftPreviewError" type="error" show-icon :closable="false" />
          <ElEmpty v-else-if="!draftPreview" :description="t('protocolConfiguration.publication.empty.preview')" />
          <template v-else>
            <p class="meta-text">{{ t('protocolConfiguration.flow.mergeBoundary') }}</p>
            <ElTable :data="draftPreview.changes" row-key="profileCode">
              <ElTableColumn :label="t('protocolConfiguration.flow.rule')" min-width="150">
                <template #default="{ row }">{{ row.afterVersion?.name || row.beforeVersion?.name || row.profileCode }}</template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.flow.change')" width="96">
                <template #default="{ row }">
                  <ElTag :type="changeTagType(row.changeType)">{{ t(`protocolConfiguration.flow.${row.changeType}`) }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.flow.before')" min-width="130">
                <template #default="{ row }">
                  {{ row.beforeVersion ? t('protocolConfiguration.flow.versionSummary', { revision: row.beforeVersion.revision, count: row.beforeVersion.mappingCount }) : '—' }}
                </template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.flow.after')" min-width="170">
                <template #default="{ row }">
                  <div v-if="row.afterVersion" class="after-cell">
                    <span>{{ t('protocolConfiguration.flow.versionSummary', { revision: row.afterVersion.revision, count: row.afterVersion.mappingCount }) }}</span>
                    <ElTag v-if="row.afterVersion.versionId === DRAFT_PREVIEW_VERSION_ID" size="small" type="info">
                      {{ t('protocolConfiguration.flow.draftPreviewBadge') }}
                    </ElTag>
                  </div>
                  <span v-else>{{ '—' }}</span>
                </template>
              </ElTableColumn>
            </ElTable>
            <details class="technical-details">
              <summary>{{ t('protocolConfiguration.flow.technical') }}</summary>
              <div class="technical-content">
                <CopyableValue :value="draftPreview.digest" />
                <p v-for="item in draftPreview.targetVersions" :key="`${item.profileCode}-${item.versionId}`" class="technical-value">
                  {{ `${item.profileCode} · ${item.versionId}` }}
                </p>
              </div>
            </details>
          </template>

          <div class="publish-actions">
            <ElButton @click="emit('fix-config')">{{ t('protocolConfiguration.flow.fixMapping') }}</ElButton>
            <ElButton
              data-testid="submit-draft-publish"
              type="primary"
              :disabled="!canSubmitDraftPublish"
              :loading="actionBusy"
              @click="submitDraftPublish"
            >
              {{ t('protocolConfiguration.publication.actions.submitPublish') }}
            </ElButton>
          </div>
        </section>
      </div>

      <section class="section-block">
        <h3>{{ t('protocolConfiguration.publication.sections.approval') }}</h3>
        <ChangeRequestControl
          business-view
          :change="changes.current.value"
          :busy="approvalBusy"
          @lookup="id => afterApprovalAction(() => changes.load(id))"
          @submit="id => afterApprovalAction(() => changes.submit(id))"
          @withdraw="id => afterApprovalAction(() => changes.withdraw(id))"
          @approve="(id, comment) => afterApprovalAction(() => changes.approve(id, comment))"
          @reject="(id, comment) => afterApprovalAction(() => changes.reject(id, comment))"
          @execute="id => afterApprovalAction(() => changes.execute(id))"
        />
      </section>

      <div v-if="currentRequestLoaded" class="closure-bar">
        <span class="meta-text">{{ t('protocolConfiguration.publication.messages.loadedClosure') }}</span>
        <ElButton
          type="success"
          :disabled="!profileCode?.trim()"
          @click="emit('open-pending-devices')"
        >
          {{ t('protocolConfiguration.actions.openPendingDevices') }}
        </ElButton>
      </div>
    </ElCard>

    <ElDrawer
      v-model="operationsOpen"
      :title="t('protocolConfiguration.sections.operationsTitle')"
      size="min(880px, 95vw)"
    >
      <ElTabs v-model="operationsTab">
        <ElTabPane :label="t('protocolConfiguration.publication.tabs.targets')" name="targets">
          <div class="drawer-section">
            <div class="section-heading">
              <h3>{{ t('protocolConfiguration.publication.sections.target') }}</h3>
              <ElButton type="primary" @click="registerOpen = true">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton>
            </div>
            <ElEmpty v-if="!targets.length" :description="t('protocolConfiguration.publication.empty.targets')" />
            <ElTable v-else :data="targets" row-key="targetId">
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.targetName')" prop="name" min-width="150" />
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.outputVersion')" prop="outputVersion" width="90" />
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.status')" width="110">
                <template #default="{ row }">
                  <ElTag :type="statusType(row.status)">{{ t(`protocolConfiguration.publication.status.${row.status}`) }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.sequence')" prop="currentSequence" width="110" />
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.topics')" min-width="200">
                <template #default="{ row }">{{ row.allowedTopics.join('、') }}</template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElTabPane>

        <ElTabPane :label="t('protocolConfiguration.publication.tabs.history')" name="history">
          <div class="drawer-section">
            <div class="section-heading">
              <ElSelect
                :model-value="targetId"
                clearable
                :placeholder="t('protocolConfiguration.publication.placeholders.target')"
                @change="selectTarget"
              >
                <ElOption
                  v-for="target in targets"
                  :key="target.targetId"
                  :value="target.targetId"
                  :disabled="target.status === 'UNKNOWN'"
                  :label="`${target.name} · ${t(`protocolConfiguration.publication.status.${target.status}`)}`"
                />
              </ElSelect>
              <ElButton :icon="RefreshCw" :loading="loading" @click="refresh">{{ t('protocolConfiguration.publication.actions.refresh') }}</ElButton>
            </div>
            <ElEmpty v-if="!targetId || !history.length" :description="t('protocolConfiguration.publication.empty.history')" />
            <ElTable v-else :data="history" row-key="sequence">
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.sequence')" prop="sequence" width="90" />
              <ElTableColumn :label="t('protocolConfiguration.flow.content')" min-width="130">
                <template #default="{ row }">
                  <ElButton link @click="showDetail(row.sequence)">{{ t('protocolConfiguration.flow.viewContent') }}</ElButton>
                </template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.status')" min-width="110">
                <template #default="{ row }">
                  <ElTag :type="statusType(row.status)">{{ t(`protocolConfiguration.publication.status.${row.status}`) }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.createdAt')" min-width="160">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.loadedAt')" min-width="160">
                <template #default="{ row }">{{ row.loadedAt ? formatDateTime(row.loadedAt) : '—' }}</template>
              </ElTableColumn>
              <ElTableColumn :label="t('protocolConfiguration.publication.labels.error')" prop="errorCode" min-width="100" />
              <ElTableColumn width="100" fixed="right">
                <template #default="{ row }">
                  <ElButton
                    v-if="row.status === 'LOADED' && row.targetId === selectedTarget?.targetId"
                    link
                    type="primary"
                    :disabled="!targetSelectable"
                    :loading="actionBusy"
                    @click="inspectPublication(row.sequence)"
                  >
                    {{ t('protocolConfiguration.publication.actions.rollback') }}
                  </ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElTabPane>

        <ElTabPane :label="t('protocolConfiguration.publication.tabs.migration')" name="migration">
          <div class="drawer-section">
            <div class="section-heading">
              <h3>{{ t('protocolConfiguration.publication.sections.versions') }}</h3>
              <div class="actions">
                <ElButton @click="openImport">{{ t('protocolConfiguration.publication.actions.import') }}</ElButton>
                <ElButton :disabled="!freezeAllowed" :loading="actionBusy" @click="freezeCurrentDraft">
                  {{ t('protocolConfiguration.publication.actions.freeze') }}
                </ElButton>
              </div>
            </div>
            <ElEmpty v-if="!versions.length" :description="t('protocolConfiguration.publication.empty.versions')" />
            <ElCheckboxGroup v-else v-model="versionIds" class="version-list">
              <ElCheckbox v-for="version in versions" :key="version.versionId" :value="version.versionId" border>
                {{ versionLabel(version) }}
              </ElCheckbox>
            </ElCheckboxGroup>
            <div class="publish-actions">
              <ElButton
                type="primary"
                :disabled="!targetSelectable || !versionIds.length"
                :loading="actionBusy"
                @click="inspectPublication()"
              >
                {{ t('protocolConfiguration.flow.confirmPublication') }}
              </ElButton>
            </div>
          </div>
        </ElTabPane>
      </ElTabs>
    </ElDrawer>

    <ElDialog v-model="registerOpen" :title="t('protocolConfiguration.publication.register.title')" width="min(520px, 92vw)">
      <ElForm label-position="top">
        <ElFormItem :label="t('protocolConfiguration.publication.labels.targetName')" required>
          <ElInput v-model="targetName" maxlength="100" />
        </ElFormItem>
        <ElFormItem :label="t('protocolConfiguration.publication.labels.outputVersion')" required>
          <ElSelect v-model="outputVersion">
            <ElOption value="V1" :label="'V1'" />
            <ElOption value="V2" :label="'V2'" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('protocolConfiguration.publication.labels.topics')" required>
          <ElInput
            v-model="allowedTopicsText"
            type="textarea"
            :rows="4"
            :placeholder="t('protocolConfiguration.publication.placeholders.topics')"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="registerOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton>
        <ElButton type="primary" :loading="actionBusy" @click="registerTarget">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="importOpen"
      :title="t('protocolConfiguration.publication.import.title')"
      width="min(760px, 94vw)"
      @closed="importPayload = ''; importPackage = null; productBindings = {}"
    >
      <ElAlert :title="t('protocolConfiguration.publication.import.notice')" type="info" show-icon :closable="false" />
      <ElInput
        v-model="importPayload"
        type="textarea"
        :rows="8"
        :placeholder="t('protocolConfiguration.publication.import.placeholder')"
      />
      <div class="actions">
        <ElButton :disabled="!importPayload.trim()" @click="parseImportPayload">{{ t('protocolConfiguration.publication.actions.parseImport') }}</ElButton>
      </div>
      <ElTable v-if="importProfiles.length" :data="importProfiles" row-key="profileId">
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.profileId')" prop="profileId" min-width="170" />
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.profileCode')" prop="profileCode" min-width="130" />
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.importKind')" width="110">
          <template #default="{ row }">{{ t(`protocolConfiguration.publication.import.${row.archived ? 'archived' : 'active'}`) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.boundProduct')" min-width="260">
          <template #default="{ row }">
            <ElSelect
              v-model="productBindings[row.profileId]"
              filterable
              remote
              :remote-method="searchProducts"
              :loading="productsLoading"
              :placeholder="t('protocolConfiguration.publication.import.productPlaceholder')"
            >
              <ElOption
                v-for="item in productOptions"
                :key="item.productId"
                :value="item.productId"
                :label="`${item.productName} · ${item.productCode}`"
              />
            </ElSelect>
          </template>
        </ElTableColumn>
      </ElTable>
      <div v-if="importProfiles.length && productPage.total > productPage.size" class="product-pager">
        <ElButton :disabled="productPage.page <= 1" @click="loadEnabledProducts(productPage.page - 1)">{{ t('protocolConfiguration.publication.actions.previous') }}</ElButton>
        <span>{{ productPageLabel() }}</span>
        <ElButton :disabled="productPage.page * productPage.size >= productPage.total" @click="loadEnabledProducts(productPage.page + 1)">{{ t('protocolConfiguration.publication.actions.next') }}</ElButton>
      </div>
      <template #footer>
        <ElButton @click="importOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton>
        <ElButton type="primary" :disabled="!importReady" :loading="actionBusy" @click="importVersions">{{ t('protocolConfiguration.publication.actions.confirmImport') }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      :model-value="Boolean(oneTimeKey)"
      :title="t('protocolConfiguration.publication.key.title')"
      :close-on-click-modal="false"
      @close="closeOneTimeKey"
    >
      <ElAlert :title="t('protocolConfiguration.publication.key.warning')" type="warning" show-icon :closable="false" />
      <ElInput :model-value="oneTimeKey || ''" readonly :aria-label="t('protocolConfiguration.publication.key.title')" />
      <template #footer>
        <ElButton type="primary" @click="closeOneTimeKey">{{ t('protocolConfiguration.publication.key.confirm') }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="confirmOpen" :title="t('protocolConfiguration.flow.confirmPublication')" width="min(900px, 95vw)">
      <template v-if="confirmation">
        <ElAlert
          :title="t(rollbackSequence === null ? 'protocolConfiguration.flow.mergeBoundary' : 'protocolConfiguration.flow.rollbackBoundary')"
          type="warning"
          :closable="false"
        />
        <p>{{ `${selectedTarget?.name ?? ''} · ${t('protocolConfiguration.publication.labels.sequence')} ${confirmation.expectedSequence}` }}</p>
        <ElTable :data="confirmation.changes" row-key="profileCode">
          <ElTableColumn :label="t('protocolConfiguration.flow.rule')">
            <template #default="{ row }">{{ row.afterVersion?.name || row.beforeVersion?.name || row.profileCode }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.change')">
            <template #default="{ row }">
              <ElTag :type="changeTagType(row.changeType)">{{ t(`protocolConfiguration.flow.${row.changeType}`) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.before')">
            <template #default="{ row }">
              {{ row.beforeVersion ? t('protocolConfiguration.flow.versionSummary', { revision: row.beforeVersion.revision, count: row.beforeVersion.mappingCount }) : '—' }}
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.after')">
            <template #default="{ row }">
              {{ row.afterVersion ? t('protocolConfiguration.flow.versionSummary', { revision: row.afterVersion.revision, count: row.afterVersion.mappingCount }) : '—' }}
            </template>
          </ElTableColumn>
        </ElTable>
        <details class="technical-details">
          <summary>{{ t('protocolConfiguration.flow.technical') }}</summary>
          <CopyableValue :value="confirmation.digest" />
          <p v-for="item in confirmation.targetVersions" :key="item.versionId" class="technical-value">
            {{ `${item.profileCode} · ${item.versionId}` }}
          </p>
        </details>
      </template>
      <template #footer>
        <ElButton @click="confirmOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton>
        <ElButton data-testid="confirm-publication" type="primary" :disabled="!confirmation" :loading="actionBusy" @click="confirmPublication">
          {{ t('protocolConfiguration.publication.actions.publish') }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="detailOpen" :title="t('protocolConfiguration.flow.content')" width="min(800px, 95vw)">
      <template v-if="detail">
        <p>{{ `${t(`protocolConfiguration.publication.status.${detail.status}`)} · ${detail.sequence}` }}</p>
        <ul>
          <li v-for="version in detail.versions" :key="version.versionId">
            {{ `${version.name} · ${t('protocolConfiguration.flow.versionSummary', { revision: version.revision, count: version.mappingCount })}` }}
          </li>
        </ul>
        <details class="technical-details">
          <summary>{{ t('protocolConfiguration.flow.technical') }}</summary>
          <CopyableValue :value="detail.digest" />
          <CopyableValue :value="detail.approvalId" />
        </details>
        <ElButton @click="afterApprovalAction(() => changes.load(detail!.approvalId)); detailOpen = false; operationsOpen = false">
          {{ t('protocolConfiguration.flow.viewApproval') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.publication-stage { display: contents; }
.publication-panel, .section-block, .drawer-section, .technical-content {
  display: grid;
  gap: var(--bec-space-group);
  min-width: 0;
}
.panel-heading, .section-heading, .actions, .publish-actions, .closure-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-space-group);
  flex-wrap: wrap;
}
.panel-heading { align-items: flex-start; }
.actions, .publish-actions { justify-content: flex-end; }
.closure-bar {
  padding: var(--bec-space-group);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
  background: var(--bec-color-surface-secondary);
}
h2, h3, p { margin: 0; }
h2 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); }
p, .meta-text { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.publication-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: var(--bec-space-section);
  align-items: start;
}
.section-block > :deep(.el-select),
.drawer-section :deep(.el-select) {
  width: 100%;
  max-width: calc(var(--bec-ref-space-64) * 8);
}
.after-cell { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.technical-details summary { cursor: pointer; padding-block: var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.technical-value { overflow-wrap: anywhere; user-select: all; }
.version-list { display: grid; gap: var(--bec-space-tight); max-height: var(--bec-navigation-width); overflow: auto; }
.version-list :deep(.el-checkbox) {
  margin: 0;
  width: 100%;
  height: auto;
  min-height: var(--bec-control-height);
  padding: var(--bec-space-tight) var(--bec-space-group);
}
.product-pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--bec-space-tight); margin-top: var(--bec-space-group); }
@media (max-width: 960px) {
  .publication-grid { grid-template-columns: 1fr; }
}
</style>
