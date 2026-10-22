<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
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
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
  Eye,
  RefreshCw,
  Search,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { telemetryConflictApi } from '../api/telemetry-conflicts'
import type { ConflictState, ConflictView, OwnerView, PageResponse } from '../models/telemetry-conflicts'
import CorrectionReview from '../components/CorrectionReview.vue'
import ConflictRecoveryPanel from '../components/ConflictRecoveryPanel.vue'

type TagType = 'success' | 'warning' | 'info' | 'danger'
type AlertType = 'success' | 'warning' | 'info' | 'error'

const route = useRoute()
const pageSize = 20
const originalEvidenceChoice = '__ORIGINAL_EVIDENCE__'
const buildingId = ref(typeof route.query.buildingId === 'string' ? route.query.buildingId : '')
const stateFilter = ref<ConflictState | undefined>()
const conflictPage = ref<PageResponse<ConflictView>>(emptyPage())
const pageLoading = ref(false)
const pageError = ref<string | null>(null)
const hasQueried = ref(false)
const queriedBuildingId = ref('')
const owner = ref<OwnerView | null>(null)
const ownerLoading = ref(false)
const ownerError = ref<string | null>(null)
const ownerUserId = ref<number | undefined>()
const ownerSaving = ref(false)
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
const detail = ref<ConflictView | null>(null)
const resolutionOpen = ref(false)
const resolutionLoading = ref(false)
const resolutionError = ref<string | null>(null)
const selectedEvidenceChoice = ref(originalEvidenceChoice)
const resolutionReason = ref('')
const evidenceReference = ref('')
const resolutionNotice = ref<string | null>(null)
let listGeneration = 0
let detailGeneration = 0

const stateOptions = computed(() => (['OPEN', 'EXPIRED', 'RESOLVED', 'PENDING_REVIEW'] as ConflictState[]).map(value => ({
  value,
  label: stateText(value),
})))
const canAssignOwner = computed(() => owner.value?.allowedActions.includes('ASSIGN_OWNER') === true)
const ownerIdValid = computed(() => Number.isInteger(ownerUserId.value) && (ownerUserId.value ?? 0) > 0)
const canResolve = computed(() => detail.value?.allowedActions.includes('RESOLVE') === true)

function emptyPage(page = 1): PageResponse<ConflictView> {
  return { page, size: pageSize, total: 0, items: [] }
}

function stateText(state: string): string {
  const key = `telemetryConflicts.states.${state}`
  return ['OPEN', 'EXPIRED', 'RESOLVED', 'PENDING_REVIEW'].includes(state)
    ? t(key)
    : t('telemetryConflicts.states.unknown', { state })
}

function stateTagType(state: string): TagType {
  if (state === 'RESOLVED') return 'success'
  if (state === 'EXPIRED') return 'warning'
  if (state === 'PENDING_REVIEW') return 'info'
  return 'danger'
}

function stateAlertType(state: string): AlertType {
  if (state === 'RESOLVED') return 'success'
  if (state === 'EXPIRED') return 'warning'
  if (state === 'PENDING_REVIEW') return 'info'
  return 'error'
}

/** EXPIRED 仍需人工核实，页面不能将过期状态推断为数值正确。 */
function stateHint(value: ConflictView): string {
  if (value.state === 'OPEN') return t('telemetryConflicts.stateHints.OPEN')
  if (value.state === 'EXPIRED') return t('telemetryConflicts.stateHints.EXPIRED')
  if (value.state === 'PENDING_REVIEW') return t('telemetryConflicts.stateHints.PENDING_REVIEW')
  return t('telemetryConflicts.stateHints.RESOLVED')
}

function modeText(mode: ConflictView['mode']): string {
  return t(`telemetryConflicts.modes.${mode}`)
}

function timeText(value: number | null | undefined): string {
  return value == null ? t('common.missing') : formatDateTime(value)
}

function valueText(value: number | null | undefined): string {
  return formatNumber(value)
}

function actionAllowed(value: ConflictView, action: string): boolean {
  return value.allowedActions.includes(action)
}

async function loadConflicts(page = conflictPage.value.page) {
  const selectedBuilding = buildingId.value.trim()
  if (!selectedBuilding) {
    pageError.value = t('telemetryConflicts.messages.buildingRequired')
    conflictPage.value = emptyPage(page)
    return
  }
  const generation = ++listGeneration
  hasQueried.value = true
  pageLoading.value = true
  pageError.value = null
  ownerLoading.value = true
  ownerError.value = null
  owner.value = null
  const [listResult, ownerResult] = await Promise.allSettled([
    telemetryConflictApi.list({ buildingId: selectedBuilding, state: stateFilter.value, page, size: pageSize }),
    telemetryConflictApi.owner(selectedBuilding),
  ])
  if (generation !== listGeneration || selectedBuilding !== buildingId.value.trim()) return
  queriedBuildingId.value = selectedBuilding
  pageLoading.value = false
  ownerLoading.value = false
  if (listResult.status === 'fulfilled') conflictPage.value = listResult.value
  else {
    conflictPage.value = emptyPage(page)
    pageError.value = requestErrorMessage(listResult.reason)
  }
  if (ownerResult.status === 'fulfilled') {
    owner.value = ownerResult.value
    ownerUserId.value = ownerResult.value.userId ?? undefined
  } else ownerError.value = requestErrorMessage(ownerResult.reason)
}

function query() {
  void loadConflicts(1)
}

function refresh() {
  void loadConflicts(conflictPage.value.page)
}

function changePage(page: number) {
  void loadConflicts(page)
}

async function assignOwner() {
  if (ownerSaving.value || queriedBuildingId.value !== buildingId.value.trim()) return
  const selectedBuilding = buildingId.value.trim()
  if (!selectedBuilding || !ownerIdValid.value || !canAssignOwner.value || ownerUserId.value == null) {
    ownerError.value = !selectedBuilding
      ? t('telemetryConflicts.messages.buildingRequired')
      : t('telemetryConflicts.messages.ownerRequired')
    return
  }
  ownerSaving.value = true
  const generation = listGeneration
  ownerError.value = null
  try {
    const result = await telemetryConflictApi.assignOwner({ buildingId: selectedBuilding, userId: ownerUserId.value })
    if (generation !== listGeneration || selectedBuilding !== buildingId.value.trim()) return
    owner.value = result
    ownerUserId.value = result.userId ?? undefined
    applyOwner(result)
    ElMessage.success(t('telemetryConflicts.messages.ownerAssigned'))
  } catch (reason) {
    if (generation === listGeneration) ownerError.value = requestErrorMessage(reason)
  } finally {
    ownerSaving.value = false
  }
}

function applyOwner(result: OwnerView) {
  conflictPage.value = {
    ...conflictPage.value,
    items: conflictPage.value.items.map(item => item.buildingId === result.buildingId ? { ...item, ownerId: result.userId } : item),
  }
  if (detail.value?.buildingId === result.buildingId) detail.value = { ...detail.value, ownerId: result.userId }
}

async function openDetail(conflict: ConflictView) {
  if (conflict.buildingId !== buildingId.value.trim()) return
  const generation = ++detailGeneration
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = null
  detail.value = null
  resolutionNotice.value = null
  try {
    const result = await telemetryConflictApi.detail(conflict.pointId, conflict.eventTime)
    if (generation !== detailGeneration) return
    detail.value = result
  } catch (reason) {
    if (generation === detailGeneration) detailError.value = requestErrorMessage(reason)
  } finally {
    if (generation === detailGeneration) detailLoading.value = false
  }
}

function openResolution() {
  if (!detail.value || !canResolve.value) return
  selectedEvidenceChoice.value = originalEvidenceChoice
  resolutionReason.value = ''
  evidenceReference.value = ''
  resolutionError.value = null
  resolutionOpen.value = true
}

async function submitResolution() {
  const current = detail.value
  const reason = resolutionReason.value.trim()
  const reference = evidenceReference.value.trim()
  if (!current || !canResolve.value || resolutionLoading.value) return
  const generation = detailGeneration
  if (!reason || !reference) {
    resolutionError.value = t('telemetryConflicts.messages.resolutionRequired')
    return
  }
  resolutionLoading.value = true
  resolutionError.value = null
  try {
    const result = await telemetryConflictApi.resolve(current.pointId, current.eventTime, {
      evidenceId: selectedEvidenceChoice.value === originalEvidenceChoice ? null : selectedEvidenceChoice.value,
      expectedRevision: current.revision,
      reason,
      evidenceReference: reference,
    })
    if (generation !== detailGeneration) return
    detail.value = result
    replaceConflict(result)
    resolutionOpen.value = false
    resolutionNotice.value = resolutionMessage(result)
    void loadConflicts(conflictPage.value.page)
  } catch (reason) {
    if (generation === detailGeneration) resolutionError.value = requestErrorMessage(reason)
  } finally {
    if (generation === detailGeneration) resolutionLoading.value = false
  }
}

async function refreshAfterApproval() {
  const current = detail.value
  if (!current) return
  ElMessage.success(t('telemetryConflicts.review.approved'))
  await Promise.all([openDetail(current), loadConflicts(conflictPage.value.page)])
}

// 切换查询范围立即撤下旧建筑操作，迟到响应不能将旧详情重新挂回当前页面。
watch(buildingId, () => {
  listGeneration++
  detailGeneration++
  queriedBuildingId.value = ''
  hasQueried.value = false
  pageLoading.value = false
  detailOpen.value = false
  resolutionOpen.value = false
  resolutionLoading.value = false
  detail.value = null
  owner.value = null
  conflictPage.value = emptyPage()
}, { flush: 'sync' })
onBeforeUnmount(() => { listGeneration++; detailGeneration++ })

function replaceConflict(result: ConflictView) {
  conflictPage.value = {
    ...conflictPage.value,
    items: conflictPage.value.items.map(item => item.pointId === result.pointId && item.eventTime === result.eventTime ? result : item),
  }
}

function resolutionMessage(result: ConflictView): string {
  if (result.state === 'PENDING_REVIEW') {
    return t('telemetryConflicts.messages.reviewPending', { versionId: result.correctionVersionId ?? t('common.missing') })
  }
  return result.mode === 'DIRECT'
    ? t('telemetryConflicts.messages.directResolved')
    : t('telemetryConflicts.messages.energyOriginalResolved')
}

onMounted(() => {
  if (buildingId.value.trim()) void loadConflicts(1)
})
</script>

<template>
  <section class="telemetry-conflict-page" :aria-label="t('telemetryConflicts.title')">
    <header class="page-heading">
      <div>
        <h1>{{ t('telemetryConflicts.title') }}</h1>
        <p>{{ t('telemetryConflicts.description') }}</p>
      </div>
    </header>

    <ElAlert v-if="pageError" :title="pageError" type="error" show-icon :closable="false" />
    <ElCard shadow="never" class="filter-panel">
      <ElForm class="filters" :aria-label="t('telemetryConflicts.title')" @submit.prevent="query">
        <ElFormItem :label="t('telemetryConflicts.filters.buildingId')">
          <ElInput v-model="buildingId" data-testid="conflict-building-id" clearable :placeholder="t('telemetryConflicts.filters.buildingPlaceholder')" @keyup.enter="query" />
        </ElFormItem>
        <ElFormItem :label="t('telemetryConflicts.filters.state')">
          <ElSelect v-model="stateFilter" clearable :placeholder="t('telemetryConflicts.filters.allStates')">
            <ElOption v-for="item in stateOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem class="filter-actions">
          <ElButton native-type="submit" type="primary" :icon="Search" :loading="pageLoading">{{ t('telemetryConflicts.actions.query') }}</ElButton>
          <ElButton :icon="RefreshCw" :disabled="!hasQueried" @click="refresh">{{ t('telemetryConflicts.actions.refresh') }}</ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>

    <ElCard v-if="hasQueried" shadow="never" class="owner-panel">
      <template #header><h2>{{ t('telemetryConflicts.owner.title') }}</h2></template>
      <ElSkeleton v-if="ownerLoading" :rows="2" animated />
      <template v-else>
        <p>{{ t('telemetryConflicts.owner.description') }}</p>
        <ElAlert v-if="ownerError" :title="ownerError" type="error" show-icon :closable="false" />
        <div v-if="owner" class="owner-content">
          <span>{{ t('telemetryConflicts.owner.current') }}{{ '：' }}{{ owner.userId ?? t('telemetryConflicts.owner.unassigned') }}</span>
          <ElInputNumber v-model="ownerUserId" data-testid="conflict-owner-id" :min="1" :precision="0" :placeholder="t('telemetryConflicts.owner.placeholder')" :disabled="!canAssignOwner" />
          <ElButton data-testid="assign-conflict-owner" type="primary" :loading="ownerSaving" :disabled="!canAssignOwner || !ownerIdValid" @click="assignOwner">{{ t('telemetryConflicts.actions.assignOwner') }}</ElButton>
        </div>
      </template>
    </ElCard>

    <ElCard v-if="hasQueried" shadow="never" class="list-panel">
      <template #header><h2>{{ t('telemetryConflicts.title') }}</h2></template>
      <ElSkeleton v-if="pageLoading && !conflictPage.items.length" :rows="6" animated />
      <ElEmpty v-else-if="!conflictPage.items.length" :description="t('common.empty')" />
      <template v-else>
        <ElTable :data="conflictPage.items" row-key="pointId">
          <ElTableColumn :label="t('telemetryConflicts.fields.pointCode')" min-width="160">
            <template #default="{ row }">{{ row.original.pointCode || row.pointId }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.eventTime')" min-width="180">
            <template #default="{ row }">{{ timeText(row.eventTime) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.state')" min-width="150">
            <template #default="{ row }"><ElTag :type="stateTagType(row.state)">{{ stateText(row.state) }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.originalValue')" min-width="120">
            <template #default="{ row }">{{ valueText(row.original.value) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.candidateCount')" min-width="110">
            <template #default="{ row }">{{ formatNumber(row.variants.length) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.effectiveValue')" min-width="120">
            <template #default="{ row }">{{ valueText(row.effectiveValue) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.fields.owner')" min-width="140">
            <template #default="{ row }">{{ row.ownerId ?? t('telemetryConflicts.owner.unassigned') }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('telemetryConflicts.actions.view')" fixed="right" min-width="120">
            <template #default="{ row }"><ElButton link type="primary" :icon="Eye" @click="openDetail(row as ConflictView)">{{ t('telemetryConflicts.actions.view') }}</ElButton></template>
          </ElTableColumn>
        </ElTable>
        <div class="pagination">
          <ElPagination background layout="total, prev, pager, next" :current-page="conflictPage.page" :page-size="conflictPage.size" :total="conflictPage.total" @current-change="changePage" />
        </div>
      </template>
    </ElCard>

    <ElEmpty v-else :description="t('telemetryConflicts.empty')" />

    <ConflictRecoveryPanel v-if="queriedBuildingId" :key="queriedBuildingId" :building-id="queriedBuildingId" :owner-id="owner?.userId ?? null" />

    <ElDrawer :model-value="detailOpen" size="min(900px, 94vw)" :title="t('telemetryConflicts.detail.title')" @update:model-value="detailOpen = false">
      <ElSkeleton v-if="detailLoading" :rows="8" animated />
      <ElAlert v-else-if="detailError" :title="detailError" type="error" show-icon :closable="false" />
      <template v-else-if="detail">
        <div class="detail-content">
          <ElAlert :title="stateHint(detail)" :type="stateAlertType(detail.state)" show-icon :closable="false" />
          <ElAlert v-if="resolutionNotice" :title="resolutionNotice" :type="detail.state === 'PENDING_REVIEW' ? 'warning' : 'success'" show-icon :closable="false" />
          <section class="detail-section">
            <h2>{{ t('telemetryConflicts.detail.original') }}</h2>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.pointId')"><CopyableValue :value="detail.pointId" /></ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.pointCode')">{{ detail.original.pointCode || t('common.missing') }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.eventTime')">{{ timeText(detail.eventTime) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.receivedTime')">{{ timeText(detail.original.receivedTime) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.originalValue')">{{ valueText(detail.original.value) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.effectiveValue')">{{ valueText(detail.effectiveValue) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.sourceSystem')">{{ detail.original.sourceSystem || t('common.missing') }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.sourcePointCode')">{{ detail.original.sourcePointCode || t('common.missing') }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.sourceDeviceId')">{{ detail.original.sourceDeviceId || t('common.missing') }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.dataQuality')">{{ formatNumber(detail.original.dataQuality) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.revision')">{{ formatNumber(detail.revision) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.selectionVersion')">{{ formatNumber(detail.selectionVersion) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.mode')">{{ modeText(detail.mode) }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="t('telemetryConflicts.fields.owner')">{{ detail.ownerId ?? t('telemetryConflicts.owner.unassigned') }}</ElDescriptionsItem>
              <ElDescriptionsItem v-if="detail.correctionVersionId" :label="t('telemetryConflicts.fields.correctionVersionId')" :span="2"><CopyableValue :value="detail.correctionVersionId" /></ElDescriptionsItem>
            </ElDescriptions>
          </section>

          <section class="detail-section">
            <h2>{{ t('telemetryConflicts.detail.variants') }}</h2>
            <ElEmpty v-if="!detail.variants.length" :description="t('telemetryConflicts.detail.noCandidates')" />
            <ElTable v-else :data="detail.variants" row-key="evidenceId">
              <ElTableColumn :label="t('telemetryConflicts.fields.candidates')" min-width="220">
                <template #default="{ row }"><CopyableValue :value="row.evidenceId" /></template>
              </ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.candidateValue')" min-width="120"><template #default="{ row }">{{ valueText(row.event.value) }}</template></ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.sourceSystem')" min-width="140"><template #default="{ row }">{{ row.event.sourceSystem || t('common.missing') }}</template></ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.dataQuality')" min-width="120"><template #default="{ row }">{{ formatNumber(row.event.dataQuality) }}</template></ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.firstSeenAt')" min-width="180"><template #default="{ row }">{{ timeText(row.firstSeenAt) }}</template></ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.lastSeenAt')" min-width="180"><template #default="{ row }">{{ timeText(row.lastSeenAt) }}</template></ElTableColumn>
              <ElTableColumn :label="t('telemetryConflicts.fields.attempts')" min-width="120"><template #default="{ row }">{{ formatNumber(row.attempts) }}</template></ElTableColumn>
            </ElTable>
          </section>

          <CorrectionReview v-if="detailOpen && detail.mode === 'ENERGY_REVIEW' && detail.state === 'PENDING_REVIEW' && detail.correctionVersionId" :conflict="detail" @approved="refreshAfterApproval" />
          <div class="drawer-actions">
            <ElButton v-if="actionAllowed(detail, 'RESOLVE')" type="primary" @click="openResolution">{{ t('telemetryConflicts.actions.resolve') }}</ElButton>
            <ElAlert v-else :title="t('telemetryConflicts.detail.noAction')" type="info" show-icon :closable="false" />
          </div>
        </div>
      </template>
    </ElDrawer>

    <ElDialog v-model="resolutionOpen" :title="t('telemetryConflicts.detail.resolution')" :width="'var(--bec-dialog-width)'" :close-on-click-modal="!resolutionLoading" :close-on-press-escape="!resolutionLoading" :show-close="!resolutionLoading">
      <ElAlert v-if="resolutionError" :title="resolutionError" type="error" show-icon :closable="false" />
      <ElForm v-if="detail" label-position="top">
        <ElFormItem :label="t('telemetryConflicts.detail.chooseValue')">
          <ElRadioGroup v-model="selectedEvidenceChoice">
            <ElRadio :value="originalEvidenceChoice">{{ t('telemetryConflicts.detail.keepOriginal') }}{{ '：' }}{{ valueText(detail.original.value) }}</ElRadio>
            <ElRadio v-for="variant in detail.variants" :key="variant.evidenceId" :value="variant.evidenceId">
              {{ valueText(variant.event.value) }}{{ ' / ' }}{{ variant.evidenceId }}
            </ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem :label="t('telemetryConflicts.fields.reason')">
          <ElInput v-model="resolutionReason" data-testid="conflict-resolution-reason" type="textarea" :rows="3" />
        </ElFormItem>
        <ElFormItem :label="t('telemetryConflicts.fields.evidenceReference')">
          <ElInput v-model="evidenceReference" data-testid="conflict-evidence-reference" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="resolutionLoading" @click="resolutionOpen = false">{{ t('telemetryConflicts.actions.cancel') }}</ElButton>
        <ElButton data-testid="submit-conflict-resolution" type="primary" :loading="resolutionLoading" @click="submitResolution">{{ t('telemetryConflicts.actions.submitResolution') }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.telemetry-conflict-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h1, h2, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); margin-top: var(--bec-space-tight); max-width: var(--bec-text-measure); }
.filters, .owner-content, .drawer-actions { display: flex; align-items: flex-end; flex-wrap: wrap; gap: var(--bec-space-group); }
.filters :deep(.el-form-item) { margin-bottom: 0; }
.filter-actions { align-self: end; }
.filter-actions :deep(.el-button + .el-button) { margin-left: 0; }
.owner-panel :deep(.el-card__body), .detail-content { display: grid; gap: var(--bec-space-group); }
.owner-content { align-items: center; }
.owner-content :deep(.el-input-number) { width: var(--bec-navigation-width); }
.list-panel :deep(.el-card__body) { padding: 0; }
.pagination { display: flex; justify-content: flex-end; padding: var(--bec-space-group) var(--bec-space-section); overflow-x: auto; }
.detail-content { padding-bottom: var(--bec-space-section); }
.detail-section { display: grid; gap: var(--bec-space-group); }
.drawer-actions :deep(.el-alert) { flex: 1; }
</style>
