<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElDialog, ElEmpty, ElInputNumber, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { daikinCurrentValue } from '../models/daikin-display'
import { useHvacBatchControl } from '../composables/use-hvac-batch-control'
import {
  HVAC_CONTROL_ACTIONS,
  isHvacControlActionValueAllowed,
  type HvacControlAction,
  type HvacControlActionOption,
  type HvacControlBatch,
  type HvacControlBatchItem,
  type HvacControlBatchItemStatus,
  type HvacControlBatchPreviewItem,
} from '../models/hvac-control'
import type { DaikinDevice } from '../models/daikin'

const props = defineProps<{ equipmentIds: string[]; devices: DaikinDevice[] }>()
const emit = defineEmits<{ clearSelection: []; completed: [batch: HvacControlBatch] }>()
const text = (key: string, params: Record<string, string | number> = {}) => t(`dashboard.daikin.batchControl.${key}`, params)
const control = useHvacBatchControl(() => props.equipmentIds)
const selectedAction = ref<HvacControlAction | null>(null)
const actionValue = ref('')
const linkedImpactConfirmed = ref(false)
const linkedImpactDetailsOpen = ref(false)
const previewDetailsOpen = ref(false)
const summaryDetailsOpen = ref(false)
const showSuccessfulItems = ref(false)
const summaryDismissed = ref(false)
const historyVisible = ref(false)
const notifiedCompleted = new Set<string>()
let summaryBatchId = ''

const deviceLabels = computed(() => new Map(props.devices.map(device => [
  device.equipmentId, device.equipmentName || device.equipmentCode || device.equipmentId,
])))
const previewItems = computed(() => control.preview.value?.items ?? [])
const selectedSet = computed(() => new Set(control.selectedIds.value))

function actionOption(item: HvacControlBatchPreviewItem, action: HvacControlAction): HvacControlActionOption | null {
  return item.actions.find(option => option.action === action) ?? null
}

function hasSafeCandidate(option: HvacControlActionOption | null): boolean {
  if (!option?.allowed) return false
  if (option.action !== 'TEMPERATURE') return option.values.length > 0
  return option.values.length > 0 || (option.minimum != null && option.maximum != null && option.minimum <= option.maximum)
}

const linkedImpactAction = computed(() => selectedAction.value === 'MODE' || selectedAction.value === 'ON_OFF')
const linkedScopeUnknown = computed(() => linkedImpactAction.value && previewItems.value.some(item => !item.impactKnown))
function actionCanBeSent(item: HvacControlBatchPreviewItem, action: HvacControlAction): boolean {
  return hasSafeCandidate(actionOption(item, action))
    && !((action === 'MODE' || action === 'ON_OFF') && !item.impactKnown)
}
function actionSupportCount(action: HvacControlAction): number {
  return previewItems.value.filter(item => actionCanBeSent(item, action)).length
}
function actionDisabled(action: HvacControlAction): boolean {
  return actionSupportCount(action) === 0
}
function actionLabel(action: HvacControlAction): string {
  return t(`dashboard.daikin.control.actionNames.${action}`)
}
function valueLabel(value: string): string {
  const key = `dashboard.daikin.control.valueNames.${value}`
  try { return t(key) } catch { return value }
}
function batchValueLabel(action: HvacControlAction, value: string): string {
  return action === 'TEMPERATURE' ? `${value} °C` : valueLabel(value)
}
function observedFieldName(action: HvacControlAction): string {
  if (action === 'ON_OFF') return 'onOff'
  if (action === 'MODE') return 'mode'
  if (action === 'AIR_FLOW') return 'airflowDirection'
  if (action === 'FAN_SPEED') return 'fanSpeed'
  return 'temperature'
}
const selectableValues = computed(() => {
  if (!selectedAction.value || selectedAction.value === 'TEMPERATURE') return []
  return Array.from(new Set(previewItems.value.flatMap(item => actionOption(item, selectedAction.value!)?.values ?? [])))
})
const temperatureRanges = computed(() => {
  const rangeCounts = new Map<string, number>()
  for (const item of previewItems.value) {
    const option = actionOption(item, 'TEMPERATURE')
    if (!hasSafeCandidate(option)) continue
    const range = option.values.length ? option.values.join('、') : `${option.minimum}～${option.maximum}`
    rangeCounts.set(range, (rangeCounts.get(range) ?? 0) + 1)
  }
  return Array.from(rangeCounts, ([range, count]) => count > 1 ? text('temperatureRangeCount', { range, count }) : range)
})
const valueSupportCount = computed(() => {
  if (!selectedAction.value || !actionValue.value.trim()) return 0
  return previewItems.value.filter(item => {
    const option = actionOption(item, selectedAction.value!)
    return actionCanBeSent(item, selectedAction.value!)
      && option != null
      && isHvacControlActionValueAllowed(option, actionValue.value.trim())
  }).length
})
const externalLinkedEquipment = computed(() => {
  if (!linkedImpactAction.value || !selectedAction.value || !actionValue.value.trim()) return []
  const labels = new Map<string, string>()
  for (const item of previewItems.value) {
    if (!actionCanBeSent(item, selectedAction.value!)) continue
    if (!isHvacControlActionValueAllowed(actionOption(item, selectedAction.value!)!, actionValue.value.trim())) continue
    for (const equipmentId of item.impactedEquipmentIds) {
      if (!selectedSet.value.has(equipmentId)) labels.set(equipmentId, item.impactedEquipmentLabels?.[equipmentId] ?? deviceLabel(equipmentId))
    }
  }
  return Array.from(labels, ([equipmentId, label]) => ({ equipmentId, label }))
})
const needsLinkedImpactConfirmation = computed(() => externalLinkedEquipment.value.length > 0)
const previewRows = computed(() => previewItems.value.map(item => {
  const option = selectedAction.value ? actionOption(item, selectedAction.value) : null
  const supported = option != null && actionValue.value.trim().length > 0
    ? actionCanBeSent(item, selectedAction.value!) && isHvacControlActionValueAllowed(option, actionValue.value.trim())
    : selectedAction.value ? actionCanBeSent(item, selectedAction.value) : hasSafeCandidate(option)
  return {
    item,
    option,
    supported,
    reasonCode: option?.reasonCode ?? item.reasonCode ?? (selectedAction.value && (selectedAction.value === 'MODE' || selectedAction.value === 'ON_OFF') && !item.impactKnown ? 'LINKED_SCOPE_UNKNOWN' : null),
  }
}))
const unsupportedPreviewRows = computed(() => previewRows.value.filter(row => !row.supported))
const canSubmit = computed(() => Boolean(
  control.preview.value
  && selectedAction.value
  && !actionDisabled(selectedAction.value)
  && valueSupportCount.value > 0
  && (!needsLinkedImpactConfirmation.value || linkedImpactConfirmed.value)
  && !control.submitting.value,
))
const currentBatch = computed(() => control.activeBatch.value)
const sortedBatchItems = computed(() => {
  const priority: Record<HvacControlBatchItemStatus, number> = {
    FAILED: 0, UNSUPPORTED: 1, UNCONFIRMED: 2, NOT_SENT: 3, CANCELLED: 4, SUPERSEDED: 5,
    DISPATCHING: 6, QUEUED: 7, ACTIVE: 8, MATCHED: 9, ALREADY_TARGET: 10,
  }
  return [...(currentBatch.value?.items ?? [])].sort((left, right) => priority[left.status] - priority[right.status])
})
const successfulItemCount = computed(() => sortedBatchItems.value.filter(item => item.status === 'MATCHED' || item.status === 'ALREADY_TARGET').length)
const visibleBatchItems = computed(() => showSuccessfulItems.value
  ? sortedBatchItems.value
  : sortedBatchItems.value.filter(item => item.status !== 'MATCHED' && item.status !== 'ALREADY_TARGET'))
const allNormalCompleted = computed(() => {
  const batch = currentBatch.value
  return Boolean(batch
    && batch.completed
    && batch.completedCount === batch.total
    && batch.attentionCount === 0
    && batch.items.every(item => item.status === 'MATCHED' || item.status === 'ALREADY_TARGET'))
})

function deviceLabel(equipmentId: string): string {
  return deviceLabels.value.get(equipmentId) ?? equipmentId
}
function reasonText(reasonCode: string | null): string {
  if (!reasonCode) return t('common.missing')
  try { return text(`reasonNames.${reasonCode}`) } catch { return text('unknownReason', { code: reasonCode }) }
}
function itemStatusLabel(item: HvacControlBatchItem): string {
  if (item.status === 'ACTIVE') return item.acceptedAt == null ? text('itemStatus.ACTIVE_SENDING') : text('itemStatus.ACTIVE_ACCEPTED')
  return text(`itemStatus.${item.status}`)
}
function batchItemFromRow(row: unknown): HvacControlBatchItem { return row as HvacControlBatchItem }
function itemStatusType(status: HvacControlBatchItemStatus): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'MATCHED' || status === 'ALREADY_TARGET') return 'success'
  if (status === 'FAILED' || status === 'NOT_SENT') return 'danger'
  if (status === 'UNSUPPORTED' || status === 'UNCONFIRMED' || status === 'CANCELLED' || status === 'SUPERSEDED') return 'warning'
  return 'primary'
}
function date(value: number | null): string { return value == null ? t('common.missing') : formatDateTime(value) }
function duration(value: number | null): string { return value == null ? t('common.missing') : text('milliseconds', { value }) }
function itemTiming(item: HvacControlBatchItem): string {
  return text('itemTiming', {
    queued: duration(item.queuedMillis),
    accepted: duration(item.acceptanceMillis),
    readback: duration(item.readbackMillis),
  })
}
function observedValue(item: HvacControlBatchItem): string {
  return item.observedValue == null ? t('common.missing') : daikinCurrentValue(observedFieldName(currentBatch.value?.action ?? 'TEMPERATURE'), item.observedValue)
}
function unitStatus(item: HvacControlBatchItem): string {
  return item.unitStatus == null ? t('common.missing') : daikinCurrentValue('unitStatus', item.unitStatus)
}
function setSelectedAction(value: string) {
  const action = value as HvacControlAction
  if (actionDisabled(action)) return
  selectedAction.value = action
  linkedImpactConfirmed.value = false
}
function openPreview() {
  if (!control.selectedIds.value.length || control.selectedIds.value.length > 100) return
  linkedImpactConfirmed.value = false
  previewDetailsOpen.value = false
  void control.createPreview()
}
async function submit() {
  if (!selectedAction.value || !canSubmit.value) return
  const result = await control.submit(selectedAction.value, actionValue.value.trim(), needsLinkedImpactConfirmation.value && linkedImpactConfirmed.value)
  if (result) {
    selectedAction.value = null
    actionValue.value = ''
    linkedImpactConfirmed.value = false
    summaryDismissed.value = false
    summaryDetailsOpen.value = false
    showSuccessfulItems.value = false
  }
}
function openHistory() {
  historyVisible.value = true
}

watch(historyVisible, visible => {
  if (visible) void control.loadHistory(1, true)
})
watch(externalLinkedEquipment, () => {
  linkedImpactConfirmed.value = false
  linkedImpactDetailsOpen.value = false
})
watch(currentBatch, batch => {
  if (!batch) {
    summaryBatchId = ''
    return
  }
  if (summaryBatchId !== batch.batchId) {
    summaryBatchId = batch.batchId
    summaryDismissed.value = false
    summaryDetailsOpen.value = false
    showSuccessfulItems.value = false
  }
  if (batch.completed && !notifiedCompleted.has(batch.batchId)) {
    notifiedCompleted.add(batch.batchId)
    // Refreshes platform monitoring only; a requested value never becomes a device reading.
    emit('completed', batch)
  }
})
</script>

<template>
  <section class="batch-control" :aria-label="text('title')">
    <div class="batch-control-bar" data-testid="batch-control-bar">
      <div class="batch-selection-summary">
        <strong>{{ text('selectedCount', { count: control.selectedIds.value.length }) }}</strong>
        <span>{{ text('selectionNotice') }}</span>
      </div>
      <div class="batch-bar-actions">
        <ElButton data-testid="batch-history-entry" @click="openHistory">{{ text('historyEntry') }}</ElButton>
        <ElButton v-if="control.selectedIds.value.length" text @click="emit('clearSelection')">{{ text('clearSelection') }}</ElButton>
        <ElButton data-testid="batch-preview" type="primary" :disabled="!control.selectedIds.value.length || control.selectedIds.value.length > 100 || control.submitting.value" :loading="control.previewLoading.value" @click="openPreview">
          {{ text('preview') }}
        </ElButton>
      </div>
    </div>

    <ElAlert v-if="control.selectedIds.value.length > 100" :title="text('tooMany', { count: control.selectedIds.value.length, max: 100 })" type="error" :closable="false" />
    <ElAlert v-if="control.previewError.value" :title="control.previewError.value" type="error" :closable="false" />

    <section v-if="control.preview.value" class="batch-preview" data-testid="batch-preview-result">
      <header class="batch-section-header">
        <div>
          <h2>{{ text('previewTitle') }}</h2>
          <p>{{ text('previewNotice') }}</p>
          <small>{{ text('previewExpiresAt', { time: date(control.preview.value.expiresAt) }) }}</small>
        </div>
        <ElButton :loading="control.previewLoading.value" @click="openPreview">{{ text('repreview') }}</ElButton>
      </header>

      <label class="form-label">
        <span>{{ text('action') }}</span>
        <ElSelect :model-value="selectedAction ?? ''" :placeholder="text('selectAction')" :aria-label="text('action')" @update:model-value="setSelectedAction(String($event))">
          <ElOption
            v-for="action in HVAC_CONTROL_ACTIONS"
            :key="action"
            :data-testid="`batch-action-${action}`"
            :value="action"
            :disabled="actionDisabled(action)"
            :label="text('actionSupport', { action: actionLabel(action), supported: actionSupportCount(action), total: previewItems.length })"
          />
        </ElSelect>
      </label>
      <ElAlert v-if="linkedScopeUnknown" :title="text('linkedScopeUnknown')" type="warning" :closable="false" />

      <label v-if="selectedAction" class="form-label">
        <span>{{ text('commonValue') }}</span>
        <ElInputNumber
          v-if="selectedAction === 'TEMPERATURE'"
          :model-value="actionValue === '' ? undefined : Number(actionValue)"
          :precision="0"
          controls-position="right"
          :aria-label="text('commonValue')"
          @update:model-value="actionValue = $event == null ? '' : String($event)"
        />
        <ElSelect v-else v-model="actionValue" :placeholder="text('selectValue')" :aria-label="text('commonValue')">
          <ElOption v-for="value in selectableValues" :key="value" :value="value" :label="valueLabel(value)" />
        </ElSelect>
        <small v-if="selectedAction === 'TEMPERATURE' && temperatureRanges.length">{{ text('temperatureRanges', { ranges: temperatureRanges.join('；') }) }}</small>
        <small v-if="actionValue">{{ text('valueSupport', { supported: valueSupportCount, total: previewItems.length }) }}</small>
      </label>

      <ElAlert v-if="selectedAction && actionValue && unsupportedPreviewRows.length" :title="text('unsupportedPreview', { count: unsupportedPreviewRows.length })" :description="text('unsupportedPreviewNotice')" type="warning" :closable="false" />
      <ElAlert v-if="selectedAction && actionValue && valueSupportCount === 0" :title="text('noValueSupport')" type="warning" :closable="false" />

      <label v-if="needsLinkedImpactConfirmation" class="linked-confirmation">
        <ElCheckbox v-model="linkedImpactConfirmed" />
        <span>{{ text('linkedConfirmation', { count: externalLinkedEquipment.length }) }}</span>
      </label>
      <section v-if="needsLinkedImpactConfirmation" class="linked-impact-details">
        <button type="button" class="detail-toggle" @click="linkedImpactDetailsOpen = !linkedImpactDetailsOpen">
          {{ linkedImpactDetailsOpen ? text('hideLinkedEquipment') : text('showLinkedEquipment', { count: externalLinkedEquipment.length }) }}
        </button>
        <ul v-if="linkedImpactDetailsOpen" data-testid="linked-impact-equipment">
          <li v-for="item in externalLinkedEquipment" :key="item.equipmentId">{{ item.label }}{{ '（' }}{{ item.equipmentId }}{{ '）' }}</li>
        </ul>
      </section>

      <div class="preview-actions">
        <ElButton data-testid="batch-submit" type="primary" :disabled="!canSubmit" :loading="control.submitting.value" @click="submit">
          {{ text('submit', { supported: valueSupportCount, total: previewItems.length }) }}
        </ElButton>
        <span>{{ text('serverFinalCheck') }}</span>
      </div>
      <ElAlert v-if="control.submitError.value" :title="control.submitError.value" :description="text('retryIdempotencyNotice')" type="error" :closable="false" />

      <section class="preview-item-details">
        <button type="button" class="detail-toggle" data-testid="preview-detail-toggle" @click="previewDetailsOpen = !previewDetailsOpen">
          {{ previewDetailsOpen ? text('collapseDetails') : text('expandPreviewDetails', { count: previewRows.length }) }}
        </button>
        <ElTable v-if="previewDetailsOpen" data-testid="preview-item-table" :data="previewRows" row-key="item.equipmentId" size="small">
          <ElTableColumn :label="text('equipment')" min-width="160"><template #default="{ row }">{{ deviceLabel(row.item.equipmentId) }}</template></ElTableColumn>
          <ElTableColumn :label="text('capability')" min-width="160">
            <template #default="{ row }"><ElTag :type="row.supported ? 'success' : 'warning'">{{ row.supported ? text('supported') : text('unsupported') }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn :label="text('reason')" min-width="220"><template #default="{ row }">{{ row.supported ? text('supportedCurrentValue') : reasonText(row.reasonCode) }}</template></ElTableColumn>
        </ElTable>
      </section>
    </section>

    <section v-if="currentBatch && !summaryDismissed" class="batch-summary" data-testid="batch-summary">
      <header class="batch-section-header">
        <div>
          <h2>{{ text('summaryTitle') }}</h2>
          <p>{{ text('summaryProgress', { completed: currentBatch.completedCount, total: currentBatch.total, queued: currentBatch.queuedCount, active: currentBatch.activeCount, attention: currentBatch.attentionCount }) }}</p>
          <p class="batch-request-summary">{{ text('batchRequest', { action: actionLabel(currentBatch.action), value: batchValueLabel(currentBatch.action, currentBatch.value) }) }}</p>
        </div>
        <div class="summary-actions">
          <ElButton v-if="!currentBatch.completed" :loading="control.activeBatchLoading.value" @click="control.loadBatch(currentBatch.batchId)">{{ text('refreshBatch') }}</ElButton>
          <ElButton v-if="!currentBatch.completed && currentBatch.queuedCount > 0" :loading="control.activeBatchLoading.value" @click="control.cancelBatch(currentBatch.batchId)">{{ text('cancelQueued') }}</ElButton>
          <ElButton v-if="allNormalCompleted" text @click="control.dismissCompletedBatch(); summaryDismissed = true">{{ text('dismissSummary') }}</ElButton>
        </div>
      </header>
      <ElAlert v-if="allNormalCompleted" :title="text('allNormalComplete')" :description="text('allNormalCompleteNotice')" type="success" :closable="false" />
      <ElAlert v-else-if="currentBatch.completed" :title="text('completedWithAttention')" :description="text('completedWithAttentionNotice')" type="warning" :closable="false" />
      <ElAlert v-if="control.batchError.value" :title="control.batchError.value" :description="text('pollingResumeNotice')" type="error" :closable="false" />

      <div class="batch-detail-actions">
        <button type="button" class="detail-toggle" data-testid="batch-detail-toggle" @click="summaryDetailsOpen = !summaryDetailsOpen">
          {{ summaryDetailsOpen ? text('collapseDetails') : text('expandBatchDetails', { count: visibleBatchItems.length }) }}
        </button>
        <ElButton v-if="summaryDetailsOpen && successfulItemCount" text @click="showSuccessfulItems = !showSuccessfulItems">
          {{ showSuccessfulItems ? text('hideSuccessfulItems') : text('showSuccessfulItems', { count: successfulItemCount }) }}
        </ElButton>
      </div>
      <template v-if="summaryDetailsOpen">
        <ElAlert v-if="!visibleBatchItems.length" :title="text('successfulItemsCollapsed')" type="info" :closable="false" />
        <ElTable v-else data-testid="batch-item-table" :data="visibleBatchItems" row-key="itemId" size="small">
          <ElTableColumn :label="text('equipment')" min-width="160"><template #default="{ row }">{{ deviceLabel(row.equipmentId) }}</template></ElTableColumn>
          <ElTableColumn :label="text('itemStatusLabel')" min-width="180"><template #default="{ row }"><ElTag :type="itemStatusType(batchItemFromRow(row).status)">{{ itemStatusLabel(batchItemFromRow(row)) }}</ElTag></template></ElTableColumn>
          <ElTableColumn :label="text('reason')" min-width="200"><template #default="{ row }">{{ reasonText(batchItemFromRow(row).reasonCode) }}</template></ElTableColumn>
          <ElTableColumn :label="text('observedValue')" min-width="140"><template #default="{ row }">{{ observedValue(batchItemFromRow(row)) }}</template></ElTableColumn>
          <ElTableColumn :label="text('unitStatus')" min-width="140"><template #default="{ row }">{{ unitStatus(batchItemFromRow(row)) }}</template></ElTableColumn>
          <ElTableColumn :label="text('itemTiming')" min-width="230"><template #default="{ row }">{{ itemTiming(batchItemFromRow(row)) }}</template></ElTableColumn>
        </ElTable>
      </template>
    </section>

    <ElDialog v-model="historyVisible" :title="text('historyTitle')" :teleported="false" width="min(920px, 96vw)">
      <ElAlert :title="text('historyNotice')" type="info" :closable="false" />
      <ElAlert v-if="control.historyError.value" :title="control.historyError.value" type="error" :closable="false" />
      <ElSkeleton v-if="control.historyLoading.value" :rows="4" animated />
      <ElEmpty v-else-if="!control.history.value.items.length" :description="text('noHistory')" />
      <template v-else>
        <ElTable :data="control.history.value.items" row-key="batchId" size="small">
          <ElTableColumn :label="text('historyCreatedAt')" min-width="170"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
          <ElTableColumn :label="text('action')" min-width="130"><template #default="{ row }">{{ actionLabel(row.action) }}{{ '：' }}{{ valueLabel(row.value) }}</template></ElTableColumn>
          <ElTableColumn :label="text('historyState')" min-width="150"><template #default="{ row }">{{ row.completed ? text('historyCompleted') : text('historyRunning') }}</template></ElTableColumn>
          <ElTableColumn :label="text('detail')" min-width="100"><template #default="{ row }"><ElButton text type="primary" @click="control.loadBatch(row.batchId); historyVisible = false">{{ text('viewBatch') }}</ElButton></template></ElTableColumn>
        </ElTable>
        <ElPagination v-if="control.history.value.total > control.history.value.size" :current-page="control.history.value.page" :page-size="control.history.value.size" :total="control.history.value.total" layout="prev, pager, next, total" @current-change="page => control.loadHistory(page)" />
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.batch-control { display: grid; gap: var(--bec-space-group); }
.batch-control-bar { position: fixed; z-index: var(--bec-layer-notices); right: var(--bec-space-page); bottom: var(--bec-space-section); left: calc(var(--bec-navigation-width) + var(--bec-space-page)); display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; padding: var(--bec-space-group) var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-brand-primary); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); }
.batch-selection-summary { display: grid; gap: var(--bec-space-tight); }
.batch-selection-summary strong, .batch-section-header h2 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.batch-selection-summary span, .batch-section-header p, .batch-section-header small, .preview-actions > span, .form-label small { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.batch-bar-actions, .summary-actions, .preview-actions { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.batch-preview, .batch-summary { display: grid; gap: var(--bec-space-group); padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.batch-section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.batch-section-header h2, .batch-section-header p, .batch-section-header small { margin: 0; }
.form-label { display: grid; gap: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 10); }
.form-label > span { color: var(--bec-color-text-secondary); }
.linked-confirmation { display: flex; align-items: flex-start; gap: var(--bec-space-tight); color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.linked-impact-details { display: grid; gap: var(--bec-space-tight); }
.linked-impact-details ul { display: grid; gap: var(--bec-space-tight); margin: 0; padding-left: var(--bec-panel-padding); color: var(--bec-color-text-secondary); }
.detail-toggle { justify-self: start; padding: 0; color: var(--bec-color-action-primary); background: transparent; border: 0; font: inherit; cursor: pointer; }
.batch-detail-actions { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.preview-item-details { display: grid; gap: var(--bec-space-tight); }
.el-pagination { max-width: 100%; overflow-x: auto; margin-top: var(--bec-space-group); }
@media (max-width: 640px) { .batch-control-bar { right: var(--bec-space-group); bottom: var(--bec-space-group); left: var(--bec-space-group); } .form-label { max-width: none; } }
</style>
