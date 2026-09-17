<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import {
  ChangeRequestControl,
  newIdempotencyKey,
  useSensitiveChange,
  type SensitiveChange,
} from '@/modules/access-control/public'
import { listDeviceProducts, type DeviceProductListItem } from '@/modules/device-onboarding/public'
import {
  CopyableValue, ElAlert, ElButton, ElCard, ElCheckbox, ElCheckboxGroup, ElDescriptions, ElDescriptionsItem, ElDialog,
  ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect, ElTable, ElTableColumn, ElTag,
  RefreshCw,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import {
  previewProtocolPublication, previewProtocolRollback, getProtocolDeploymentDetail,
  freezeProtocolVersion,
  importProtocolVersions,
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  registerProtocolPublicationTarget,
  requestProtocolPublication,
  requestProtocolRollback,
} from '../api/protocol-configuration'
import type {
  ProtocolPublicationPreview, ProtocolDeploymentDetail,
  ParsedProtocolImport,
  ProtocolDeployment,
  ProtocolFrozenVersion,
  ProtocolOutputVersion,
  ProtocolPublicationTarget,
} from '../models/protocol-configuration'
import { parseProtocolExportPackage } from '../models/protocol-configuration'

const props = defineProps<{
  active?: boolean
  freezeReady?: boolean
  draftId: string | null
  draftRevision: number | null
  productEnabled: boolean
  selectedProduct: DeviceProductListItem | null
}>()

watch(() => props.active, active => { if (active === false) stopPolling(); else void refresh().catch(stopPolling) })
const changes = useSensitiveChange()
const confirmation = ref<ProtocolPublicationPreview | null>(null)
const detail = ref<ProtocolDeploymentDetail | null>(null)
const confirmOpen = ref(false)
const detailOpen = ref(false)
const rollbackSequence = ref<number | null>(null)
let confirmationGeneration = 0
let detailGeneration = 0
const lastRefresh = ref<number | null>(null)
const pollExpired = ref(false)
let pollStarted = 0
let pollKey = ''
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function schedulePolling() {
  stopPolling()
  const target = selectedTarget.value
  if (disposed || props.active === false || document.hidden || !target || target.status !== 'PENDING_SYNC') return
  const key = target.targetId + ':' + target.currentSequence
  if (key !== pollKey) { pollKey = key; pollStarted = Date.now(); pollExpired.value = false }
  if (Date.now() - pollStarted >= 120000) { pollExpired.value = true; return }
  pollTimer = setTimeout(() => { void refresh().catch(stopPolling) }, 5000)
}
function visibilityChanged() { if (props.active === false || document.hidden) stopPolling(); else void refresh().catch(stopPolling) }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onBeforeUnmount(() => { disposed = true; ++refreshGeneration; ++historyGeneration; stopPolling(); ++confirmationGeneration; ++detailGeneration; document.removeEventListener('visibilitychange', visibilityChanged) })

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
  } catch (reason) { error.value = publicationError(reason) }
  finally { actionBusy.value = false }
}
async function confirmPublication() {
  const value = confirmation.value
  if (!value || value.targetId !== targetId.value || value.expectedSequence !== selectedTarget.value?.currentSequence) { confirmOpen.value = false; return }
  confirmOpen.value = false
  if (rollbackSequence.value === null) await publish()
  else await rollback(value.targetId, rollbackSequence.value)
  confirmation.value = null
}
async function showDetail(sequence: number) {
  const owner = ++detailGeneration
  const selected = targetId.value
  detail.value = null
  try { const result = await getProtocolDeploymentDetail(selected, sequence); if (owner === detailGeneration && selected === targetId.value) { detail.value = result; detailOpen.value = true } }
  catch (reason) { error.value = publicationError(reason) }
}

const targets = ref<ProtocolPublicationTarget[]>([])
const versions = ref<ProtocolFrozenVersion[]>([])
const history = ref<ProtocolDeployment[]>([])
const targetId = ref('')
const versionIds = ref<string[]>([])
watch([targetId, versionIds], () => { confirmation.value = null; confirmOpen.value = false; ++confirmationGeneration }, { deep: true })
const loading = ref(false)
const actionBusy = ref(false)
const error = ref<string | null>(null)
function publicationError(reason: unknown): string {
  const code = requestErrorCode(reason)
  if (code) {
    if (code === 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR') return t('protocolConfiguration.publication.validation.ambiguous')
    if (code === 'PROTOCOL_VALIDATION_FAILED' || code?.startsWith('PROTOCOL_SNAPSHOT_')) return t('protocolConfiguration.publication.validation.invalidConfiguration')
  }
  return requestErrorMessage(reason)
}
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
let historyGeneration = 0
let refreshGeneration = 0

const selectedTarget = computed(() => targets.value.find(item => item.targetId === targetId.value) ?? null)
const targetSelectable = computed(() => selectedTarget.value !== null && selectedTarget.value.status !== 'UNKNOWN')
const approvalBusy = computed(() => changes.pending.value.size > 0)
const freezeAllowed = computed(() => Boolean(props.freezeReady !== false && props.draftId && props.draftRevision && props.productEnabled))
const importProfiles = computed(() => importPackage.value?.profiles ?? [])
const importReady = computed(() => importProfiles.value.length > 0 && importProfiles.value.every(item => Boolean(productBindings.value[item.profileId])))

onMounted(() => { void refresh().catch(() => undefined) })
watch(importPayload, () => {
  importPackage.value = null
  productBindings.value = {}
})

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

async function refresh() {
  const owner = ++refreshGeneration
  ++historyGeneration
  history.value = []
  loading.value = true
  error.value = null
  try {
    const [nextTargets, nextVersions] = await Promise.all([listProtocolPublicationTargets(), listProtocolVersions()])
    if (owner !== refreshGeneration) return
    targets.value = nextTargets
    versions.value = nextVersions
    lastRefresh.value = Date.now()
    if (confirmation.value && nextTargets.find(item => item.targetId === confirmation.value?.targetId)?.currentSequence !== confirmation.value.expectedSequence) { confirmation.value = null; confirmOpen.value = false; ++confirmationGeneration }
    if (targetId.value && !nextTargets.some(item => item.targetId === targetId.value)) targetId.value = ''
    if (targetId.value) await loadHistory(targetId.value)
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
  try { await loadHistory(value); schedulePolling() } catch { stopPolling() }
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
    const result = await registerProtocolPublicationTarget({ name: targetName.value.trim(), outputVersion: outputVersion.value, allowedTopics: topics })
    oneTimeKey.value = result.oneTimeKey
    targets.value = [result.target, ...targets.value.filter(item => item.targetId !== result.target.targetId)]
    targetId.value = result.target.targetId
    ++historyGeneration
    history.value = []
    registerOpen.value = false
    targetName.value = ''
    allowedTopicsText.value = ''
    try {
      targets.value = await listProtocolPublicationTargets()
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

async function publish() {
  const target = selectedTarget.value
  if (!target || target.status === 'UNKNOWN') {
    error.value = t('protocolConfiguration.publication.validation.targetUnavailable')
    return
  }
  if (!versionIds.value.length) {
    error.value = t('protocolConfiguration.publication.validation.versionRequired')
    return
  }
  await createApproval(() => requestProtocolPublication({
    targetId: target.targetId,
    expectedSequence: target.currentSequence,
    versionIds: [...versionIds.value],
    idempotencyKey: newIdempotencyKey(),
  }))
}

async function rollback(deploymentTargetId: string, sequence: number) {
  const target = selectedTarget.value
  const deployment = history.value.find(item => item.targetId === deploymentTargetId && item.sequence === sequence)
  if (!target || !deployment || target.status === 'UNKNOWN' || deployment.targetId !== target.targetId || deployment.status !== 'LOADED') {
    error.value = t('protocolConfiguration.publication.validation.rollbackTarget')
    return
  }
  await createApproval(() => requestProtocolRollback({
    targetId: target.targetId,
    historicalSequence: deployment.sequence,
    expectedSequence: target.currentSequence,
    idempotencyKey: newIdempotencyKey(),
  }))
}

async function createApproval(action: () => Promise<SensitiveChange>) {
  actionBusy.value = true
  error.value = null
  try {
    changes.current.value = await action()
    ElMessage.success(t('protocolConfiguration.publication.messages.requestCreated'))
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
    // 公共审批能力保留标准错误，发布面板刷新只读取后端状态。
  }
}

function statusType(status: string) {
  if (status === 'LOADED' || status === 'READY') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PENDING_SYNC') return 'warning'
  return 'info'
}

function versionLabel(version: ProtocolFrozenVersion) {
  return t('protocolConfiguration.publication.versionLabel', { name: version.configuration.name, revision: version.draftRevision, digest: version.digest })
}

function productPageLabel() {
  return t('protocolConfiguration.publication.productPage', { current: productPage.value.page, total: Math.max(1, Math.ceil(productPage.value.total / productPage.value.size)) })
}
defineExpose({ freezeCurrentDraft })
</script>

<template>
  <ElCard shadow="never" class="publication-panel">
    <template #header>
      <div class="panel-heading">
        <div><h2>{{ t('protocolConfiguration.publication.title') }}</h2><p>{{ t('protocolConfiguration.publication.description') }}</p></div>
        <div class="actions"><ElButton :icon="RefreshCw" :loading="loading" @click="refresh">{{ t('protocolConfiguration.publication.actions.refresh') }}</ElButton><details><summary>{{ t('protocolConfiguration.flow.more') }}</summary><ElButton @click="openImport">{{ t('protocolConfiguration.publication.actions.import') }}</ElButton><ElButton @click="registerOpen = true">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton></details></div>
      </div>
    </template>

    <p v-if="lastRefresh">{{ t('protocolConfiguration.flow.lastRefresh') }} {{ formatDateTime(lastRefresh) }}</p><ElAlert v-if="pollExpired" :title="t('protocolConfiguration.flow.pollExpired')" type="info" :closable="false" />
    <ElAlert :title="t('protocolConfiguration.publication.localBoundary')" type="info" show-icon :closable="false" />
    <ElAlert v-if="error || changes.error.value" :title="error || changes.error.value || ''" type="error" show-icon :closable="false" />

    <div class="publication-grid">
      <section class="section-block">
        <div class="section-heading"><h3>{{ t('protocolConfiguration.publication.sections.versions') }}</h3><ElButton type="primary" :disabled="!freezeAllowed" :loading="actionBusy" @click="freezeCurrentDraft">{{ t('protocolConfiguration.publication.actions.freeze') }}</ElButton></div>
        <ElAlert v-if="!freezeAllowed" :title="t('protocolConfiguration.publication.freezeHint')" type="warning" :closable="false" />
        <ElEmpty v-if="!versions.length" :description="t('protocolConfiguration.publication.empty.versions')" />
        <ElCheckboxGroup v-else v-model="versionIds" class="version-list">
          <ElCheckbox v-for="version in versions" :key="version.versionId" :value="version.versionId" border>
            {{ versionLabel(version) }}
          </ElCheckbox>
        </ElCheckboxGroup>
      </section>

      <section class="section-block">
        <h3>{{ t('protocolConfiguration.publication.sections.target') }}</h3>
        <ElSelect :model-value="targetId" clearable :placeholder="t('protocolConfiguration.publication.placeholders.target')" @change="selectTarget">
          <ElOption v-for="target in targets" :key="target.targetId" :value="target.targetId" :disabled="target.status === 'UNKNOWN'" :label="`${target.name} · ${t(`protocolConfiguration.publication.status.${target.status}`)}`" />
        </ElSelect>
        <ElDescriptions v-if="selectedTarget" :column="2" border>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.outputVersion')">{{ selectedTarget.outputVersion }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.status')"><ElTag :type="statusType(selectedTarget.status)">{{ t(`protocolConfiguration.publication.status.${selectedTarget.status}`) }}</ElTag></ElDescriptionsItem>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.sequence')">{{ selectedTarget.currentSequence }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.lastSeen')">{{ selectedTarget.lastSeen ? formatDateTime(selectedTarget.lastSeen) : '—' }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.topics')">{{ selectedTarget.allowedTopics.join('、') }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('protocolConfiguration.publication.labels.error')">{{ selectedTarget.errorCode || '—' }}</ElDescriptionsItem>
        </ElDescriptions>
        <ElButton type="primary" :disabled="!targetSelectable || !versionIds.length" :loading="actionBusy" @click="inspectPublication()">{{ t('protocolConfiguration.flow.confirmPublication') }}</ElButton>
      </section>
    </div>

    <section class="section-block">
      <h3>{{ t('protocolConfiguration.publication.sections.history') }}</h3>
      <ElEmpty v-if="!targetId || !history.length" :description="t('protocolConfiguration.publication.empty.history')" />
      <ElTable v-else :data="history" row-key="sequence">
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.sequence')" prop="sequence" width="90" />
        <ElTableColumn :label="t('protocolConfiguration.flow.content')" min-width="180"><template #default="{ row }"><ElButton link @click="showDetail(row.sequence)">{{ t('protocolConfiguration.flow.viewContent') }}</ElButton></template></ElTableColumn>

        <ElTableColumn :label="t('protocolConfiguration.publication.labels.status')" min-width="110"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ t(`protocolConfiguration.publication.status.${row.status}`) }}</ElTag></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.createdAt')" min-width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.loadedAt')" min-width="170"><template #default="{ row }">{{ row.loadedAt ? formatDateTime(row.loadedAt) : '—' }}</template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.error')" prop="errorCode" min-width="120" />
        <ElTableColumn width="100" fixed="right"><template #default="{ row }"><ElButton v-if="row.status === 'LOADED' && row.targetId === selectedTarget?.targetId" link type="primary" :disabled="!targetSelectable" :loading="actionBusy" @click="inspectPublication(row.sequence)">{{ t('protocolConfiguration.publication.actions.rollback') }}</ElButton></template></ElTableColumn>
      </ElTable>
    </section>

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

    <ElDialog v-model="registerOpen" :title="t('protocolConfiguration.publication.register.title')" width="min(520px, 92vw)">
      <ElForm label-position="top">
        <ElFormItem :label="t('protocolConfiguration.publication.labels.targetName')" required><ElInput v-model="targetName" maxlength="100" /></ElFormItem>
        <ElFormItem :label="t('protocolConfiguration.publication.labels.outputVersion')" required><ElSelect v-model="outputVersion"><ElOption value="V1" :label="'V1'" /><ElOption value="V2" :label="'V2'" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('protocolConfiguration.publication.labels.topics')" required><ElInput v-model="allowedTopicsText" type="textarea" :rows="4" :placeholder="t('protocolConfiguration.publication.placeholders.topics')" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="registerOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton><ElButton type="primary" :loading="actionBusy" @click="registerTarget">{{ t('protocolConfiguration.publication.actions.register') }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="importOpen" :title="t('protocolConfiguration.publication.import.title')" width="min(760px, 94vw)" @closed="importPayload = ''; importPackage = null; productBindings = {}">
      <ElAlert :title="t('protocolConfiguration.publication.import.notice')" type="info" show-icon :closable="false" />
      <ElInput v-model="importPayload" type="textarea" :rows="8" :placeholder="t('protocolConfiguration.publication.import.placeholder')" />
      <div class="actions"><ElButton :disabled="!importPayload.trim()" @click="parseImportPayload">{{ t('protocolConfiguration.publication.actions.parseImport') }}</ElButton></div>
      <ElTable v-if="importProfiles.length" :data="importProfiles" row-key="profileId">
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.profileId')" prop="profileId" min-width="170" />
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.profileCode')" prop="profileCode" min-width="130" />
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.importKind')" width="110"><template #default="{ row }">{{ t(`protocolConfiguration.publication.import.${row.archived ? 'archived' : 'active'}`) }}</template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.publication.labels.boundProduct')" min-width="260"><template #default="{ row }"><ElSelect v-model="productBindings[row.profileId]" filterable remote :remote-method="searchProducts" :loading="productsLoading" :placeholder="t('protocolConfiguration.publication.import.productPlaceholder')"><ElOption v-for="item in productOptions" :key="item.productId" :value="item.productId" :label="`${item.productName} · ${item.productCode}`" /></ElSelect></template></ElTableColumn>
      </ElTable>
      <div v-if="importProfiles.length" class="product-pager"><ElButton :disabled="productPage.page <= 1" @click="loadEnabledProducts(productPage.page - 1)">{{ t('protocolConfiguration.publication.actions.previous') }}</ElButton><span>{{ productPageLabel() }}</span><ElButton :disabled="productPage.page * productPage.size >= productPage.total" @click="loadEnabledProducts(productPage.page + 1)">{{ t('protocolConfiguration.publication.actions.next') }}</ElButton></div>
      <template #footer><ElButton @click="importOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton><ElButton type="primary" :disabled="!importReady" :loading="actionBusy" @click="importVersions">{{ t('protocolConfiguration.publication.actions.confirmImport') }}</ElButton></template>
    </ElDialog>

    <ElDialog :model-value="Boolean(oneTimeKey)" :title="t('protocolConfiguration.publication.key.title')" :close-on-click-modal="false" @close="closeOneTimeKey">
      <ElAlert :title="t('protocolConfiguration.publication.key.warning')" type="warning" show-icon :closable="false" />
      <ElInput :model-value="oneTimeKey || ''" readonly :aria-label="t('protocolConfiguration.publication.key.title')" />
      <template #footer><ElButton type="primary" @click="closeOneTimeKey">{{ t('protocolConfiguration.publication.key.confirm') }}</ElButton></template>
    </ElDialog>
    <ElDialog v-model="confirmOpen" :title="t('protocolConfiguration.flow.confirmPublication')" width="min(900px, 95vw)">
      <template v-if="confirmation">
        <ElAlert :title="t(rollbackSequence === null ? 'protocolConfiguration.flow.mergeBoundary' : 'protocolConfiguration.flow.rollbackBoundary')" type="warning" :closable="false" />
        <p>{{ selectedTarget?.name }}{{ "·" }}{{ t('protocolConfiguration.publication.labels.sequence') }} {{ confirmation.expectedSequence }}</p>
        <ElTable :data="confirmation.changes" row-key="profileCode">
          <ElTableColumn :label="t('protocolConfiguration.flow.rule')"><template #default="{ row }">{{ row.afterVersion?.name || row.beforeVersion?.name || row.profileCode }}</template></ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.change')"><template #default="{ row }">{{ t('protocolConfiguration.flow.' + row.changeType) }}</template></ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.before')"><template #default="{ row }">{{ row.beforeVersion ? t('protocolConfiguration.flow.versionSummary', { revision: row.beforeVersion.revision, count: row.beforeVersion.mappingCount }) : '—' }}</template></ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.flow.after')"><template #default="{ row }">{{ row.afterVersion ? t('protocolConfiguration.flow.versionSummary', { revision: row.afterVersion.revision, count: row.afterVersion.mappingCount }) : '—' }}</template></ElTableColumn>
        </ElTable>
        <details><summary>{{ t('protocolConfiguration.flow.technical') }}</summary><CopyableValue :value="confirmation.digest" /><p v-for="item in confirmation.targetVersions" :key="item.versionId" class="technical-value">{{ item.profileCode }}{{ "·" }}{{ item.versionId }}</p></details>
      </template>
      <template #footer><ElButton @click="confirmOpen = false">{{ t('protocolConfiguration.publication.actions.cancel') }}</ElButton><ElButton data-testid="confirm-publication" type="primary" :disabled="!confirmation" :loading="actionBusy" @click="confirmPublication">{{ t('protocolConfiguration.publication.actions.publish') }}</ElButton></template>
    </ElDialog>
    <ElDialog v-model="detailOpen" :title="t('protocolConfiguration.flow.content')" width="min(800px, 95vw)">
      <template v-if="detail"><p>{{ t('protocolConfiguration.publication.status.' + detail.status) }}{{ "·" }}{{ detail.sequence }}</p><ul><li v-for="version in detail.versions" :key="version.versionId">{{ version.name }}{{ "·" }}{{ t('protocolConfiguration.flow.versionSummary', { revision: version.revision, count: version.mappingCount }) }}</li></ul><details><summary>{{ t('protocolConfiguration.flow.technical') }}</summary><CopyableValue :value="detail.digest" /><CopyableValue :value="detail.approvalId" /></details><ElButton @click="afterApprovalAction(() => changes.load(detail!.approvalId)); detailOpen = false">{{ t('protocolConfiguration.flow.viewApproval') }}</ElButton></template>
    </ElDialog>
  </ElCard>
</template>

<style scoped>
summary { cursor: pointer; padding-block: var(--bec-space-tight); }
.technical-value { overflow-wrap: anywhere; user-select: all; }
.publication-panel, .section-block { display: grid; gap: var(--bec-space-group); }
.panel-heading, .section-heading, .actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading { align-items: flex-start; }
.actions { justify-content: flex-end; }
h2, h3, p { margin: 0; }
h2 { font-size: var(--bec-font-size-title); }
h3 { font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); }
p { margin-top: var(--bec-space-tight); color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.publication-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-section); }
.version-list { display: grid; gap: var(--bec-space-tight); max-height: var(--bec-navigation-width); overflow: auto; }
.version-list :deep(.el-checkbox) { margin: 0; width: 100%; height: auto; min-height: var(--bec-control-height); padding: var(--bec-space-tight) var(--bec-space-group); }
.product-pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--bec-space-tight); margin-top: var(--bec-space-group); }
@media (max-width: 960px) { .publication-grid { grid-template-columns: 1fr; } }
</style>
