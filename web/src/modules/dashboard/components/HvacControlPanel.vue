<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElEmpty, ElInput, ElInputNumber, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { useHvacControl } from '../composables/use-hvac-control'
import {
  canHvacControlClose,
  canHvacControlConfirm,
  canHvacControlRecheck,
  canHvacControlRelease,
  type HvacControlAction,
  type HvacControlOperation,
} from '../models/hvac-control'

const props = defineProps<{ equipmentId: string; refreshTick: number }>()
const text = (key: string, params: Record<string, string | number> = {}) => t(`dashboard.daikin.${key}`, params)
const control = useHvacControl(() => props.equipmentId)
const selectedAction = ref<HvacControlAction | ''>('')
const selectedEnumValue = ref('')
const selectedTemperature = ref<number | undefined>(undefined)
const confirmed = ref(false)
const closeTarget = ref<HvacControlOperation | null>(null)
const closeReason = ref('')
const closeConfirmed = ref(false)
const fieldTarget = ref<HvacControlOperation | null>(null)
const fieldAction = ref<'confirm' | 'release' | null>(null)
const fieldReason = ref('')
const fieldConfirmed = ref(false)

const options = computed(() => control.options.value?.actions ?? [])
const allowedActions = computed(() => options.value.filter(item => item.allowed))
const selectedOption = computed(() => options.value.find(item => item.action === selectedAction.value) ?? null)
const temperatureRange = computed(() => selectedOption.value?.action === 'TEMPERATURE'
  && (selectedOption.value.minimum != null || selectedOption.value.maximum != null))
const requestedValue = computed(() => temperatureRange.value
  ? selectedTemperature.value == null ? '' : String(selectedTemperature.value)
  : selectedEnumValue.value)
const canSubmit = computed(() => Boolean(
  control.options.value?.canControl
  && selectedOption.value?.allowed
  && control.options.value.revision
  && requestedValue.value.trim()
  && confirmed.value
  && !control.hasInFlightOperation.value
  && !control.busy.value,
))
const closeReasonValid = computed(() => {
  const length = closeReason.value.trim().length
  return length >= 5 && length <= 500
})
const fieldReasonValid = computed(() => {
  const length = fieldReason.value.trim().length
  return length >= 5 && length <= 500
})

function actionLabel(action: HvacControlAction): string {
  const keys: Record<HvacControlAction, string> = {
    ON_OFF: 'control.actionNames.ON_OFF', TEMPERATURE: 'control.actionNames.TEMPERATURE', MODE: 'control.actionNames.MODE',
    AIR_FLOW: 'control.actionNames.AIR_FLOW', FAN_SPEED: 'control.actionNames.FAN_SPEED',
  }
  return text(keys[action])
}
function valueLabel(value: string): string {
  const keys: Record<string, string> = {
    on: 'control.valueNames.on', off: 'control.valueNames.off', cooling: 'control.valueNames.cooling', heating: 'control.valueNames.heating',
    dependent: 'control.valueNames.dependent', fan: 'control.valueNames.fan', dry: 'control.valueNames.dry',
    automaticCooling: 'control.valueNames.automaticCooling', automaticHeating: 'control.valueNames.automaticHeating', ventilationMonitorOnly: 'control.valueNames.ventilationMonitorOnly',
    low: 'control.valueNames.low', middleLow: 'control.valueNames.middleLow', middle: 'control.valueNames.middle',
    middleHigh: 'control.valueNames.middleHigh', high: 'control.valueNames.high', automatic: 'control.valueNames.automatic',
    airFlowZero: 'control.valueNames.airFlowZero', airFlowOne: 'control.valueNames.airFlowOne', airFlowTwo: 'control.valueNames.airFlowTwo',
    airFlowThree: 'control.valueNames.airFlowThree', airFlowFour: 'control.valueNames.airFlowFour', airFlowSeven: 'control.valueNames.airFlowSeven',
    airFlowAuto: 'control.valueNames.airFlowAuto',
  }
  return keys[value] ? text(keys[value]!) : value
}
function reasonText(reasonCode: string | null): string {
  if (!reasonCode) return text('control.noReason')
  const keys: Record<string, string> = {
    DISABLED: 'control.reasonNames.DISABLED', FORBIDDEN: 'control.reasonNames.FORBIDDEN', BUSY: 'control.reasonNames.BUSY',
    TARGET_UNAVAILABLE: 'control.reasonNames.TARGET_UNAVAILABLE', IDENTITY_CONFLICT: 'control.reasonNames.IDENTITY_CONFLICT',
    CONTROLLER_UNAVAILABLE: 'control.reasonNames.CONTROLLER_UNAVAILABLE', DEVICE_UNAVAILABLE: 'control.reasonNames.DEVICE_UNAVAILABLE',
    LINKED_DEVICE_UNCONFIRMED: 'control.reasonNames.LINKED_DEVICE_UNCONFIRMED', AIR_FLOW_UNCONFIRMED: 'control.reasonNames.AIR_FLOW_UNCONFIRMED',
    CAPABILITY_UNKNOWN: 'control.reasonNames.CAPABILITY_UNKNOWN', TEMPERATURE_RANGE_UNKNOWN: 'control.reasonNames.TEMPERATURE_RANGE_UNKNOWN',
    TEMPERATURE_MODE_UNCONFIRMED: 'control.reasonNames.TEMPERATURE_MODE_UNCONFIRMED', REVISION_CHANGED: 'control.reasonNames.REVISION_CHANGED',
    PROVIDER_UNAVAILABLE: 'control.reasonNames.PROVIDER_UNAVAILABLE', PREFLIGHT_UNAVAILABLE: 'control.reasonNames.PREFLIGHT_UNAVAILABLE',
    PREFLIGHT_EXPIRED: 'control.reasonNames.PREFLIGHT_EXPIRED', SOURCE_PAUSED: 'control.reasonNames.SOURCE_PAUSED',
    SEND_RESULT_UNKNOWN: 'control.reasonNames.SEND_RESULT_UNKNOWN', READBACK_UNAVAILABLE: 'control.reasonNames.READBACK_UNAVAILABLE',
    READBACK_NOT_CONFIRMED: 'control.reasonNames.READBACK_NOT_CONFIRMED', READBACK_INVALID: 'control.reasonNames.READBACK_INVALID', OBSERVED_MATCH_RESULT_UNKNOWN: 'control.reasonNames.OBSERVED_MATCH_RESULT_UNKNOWN',
    CONTROLLER_BUSY: 'control.reasonNames.CONTROLLER_BUSY', ACTION_UNCONFIRMED: 'control.reasonNames.ACTION_UNCONFIRMED', CHECK_BUSY: 'control.reasonNames.CHECK_BUSY', ONSITE_CONFIRMATION_REQUIRED: 'control.reasonNames.ONSITE_CONFIRMATION_REQUIRED',
    MANUALLY_CLOSED_UNKNOWN: 'control.reasonNames.MANUALLY_CLOSED_UNKNOWN', MANUALLY_CLOSED_UNCONFIRMED: 'control.reasonNames.MANUALLY_CLOSED_UNCONFIRMED',
  }
  if (keys[reasonCode]) return text(keys[reasonCode]!)
  return reasonCode.startsWith('VENDOR_') ? text('control.vendorReason', { code: reasonCode }) : text('control.unknownReason', { code: reasonCode })
}
function statusLabel(status: HvacControlOperation['status']): string {
  return text(`control.statusNames.${status}`)
}
function statusType(status: HvacControlOperation['status']): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'OBSERVED_MATCH') return 'info'
  if (status === 'REJECTED' || status === 'NOT_SENT') return 'danger'
  if (status === 'UNKNOWN' || status === 'UNCONFIRMED' || status === 'OBSERVED_STABLE') return 'warning'
  if (status === 'CLOSED') return 'info'
  return 'primary'
}
function operationMessage(operation: HvacControlOperation): string {
  if (operation.status === 'UNKNOWN' && operation.cloudStable) return text('control.resultMessages.unknownCloudStable')
  if (operation.status === 'UNKNOWN' && operation.observedValue === operation.value) return text('control.resultMessages.unknownObservedMatch')
  if (operation.status === 'CLOSED' && operation.isolated) return text('control.resultMessages.closedIsolated')
  return text(`control.resultMessages.${operation.status}`)
}
function operationFromRow(row: unknown): HvacControlOperation {
  return row as HvacControlOperation
}
function date(value: number | null): string { return value == null ? t('common.missing') : formatDateTime(value) }
function unitStatus(value: string | null): string {
  const keys: Record<string, string> = { operating: 'statusNames.operating', stopped: 'statusNames.stopped' }
  return value == null ? t('common.missing') : keys[value] ? text(keys[value]!) : value
}
function resetDraft() {
  selectedAction.value = ''
  selectedEnumValue.value = ''
  selectedTemperature.value = undefined
  confirmed.value = false
  closeTarget.value = null
  closeReason.value = ''
  closeConfirmed.value = false
  fieldTarget.value = null
  fieldAction.value = null
  fieldReason.value = ''
  fieldConfirmed.value = false
}
async function submit() {
  if (!selectedOption.value || !canSubmit.value) return
  const result = await control.submit(selectedOption.value.action, requestedValue.value)
  if (result) confirmed.value = false
}
function openClose(operation: HvacControlOperation) {
  fieldTarget.value = null
  fieldAction.value = null
  closeTarget.value = operation
  closeReason.value = ''
  closeConfirmed.value = false
}
function openFieldAction(operation: HvacControlOperation, action: 'confirm' | 'release') {
  closeTarget.value = null
  fieldTarget.value = operation
  fieldAction.value = action
  fieldReason.value = ''
  fieldConfirmed.value = false
}
async function closeOperation() {
  if (!closeTarget.value || !closeConfirmed.value || !closeReasonValid.value) return
  const result = await control.close(closeTarget.value.operationId, closeReason.value)
  if (result) {
    closeTarget.value = null
    closeReason.value = ''
    closeConfirmed.value = false
  }
}
async function submitFieldAction() {
  if (!fieldTarget.value || !fieldAction.value || !fieldConfirmed.value || !fieldReasonValid.value) return
  const result = fieldAction.value === 'confirm'
    ? await control.confirm(fieldTarget.value.operationId, fieldReason.value)
    : await control.release(fieldTarget.value.operationId, fieldReason.value)
  if (result) {
    fieldTarget.value = null
    fieldAction.value = null
    fieldReason.value = ''
    fieldConfirmed.value = false
  }
}

watch(() => props.equipmentId, resetDraft)
watch(() => props.refreshTick, () => { void control.refresh() })
watch(selectedAction, () => {
  selectedEnumValue.value = ''
  selectedTemperature.value = undefined
  confirmed.value = false
})
watch(requestedValue, () => { confirmed.value = false })
</script>

<template>
  <section class="control-panel" :aria-label="text('control.title')">
    <header class="panel-heading">
      <div>
        <h3>{{ text('control.title') }}</h3>
        <p>{{ text('control.notice') }}</p>
      </div>
      <ElButton :loading="control.refreshing.value" @click="control.refresh()">{{ text('refresh') }}</ElButton>
    </header>

    <ElSkeleton v-if="control.loading.value" :rows="4" animated />
    <template v-else>
      <ElAlert v-if="control.error.value" :title="control.error.value" type="error" :closable="false" />
      <ElAlert
        v-if="control.options.value && !control.options.value.canControl"
        :title="text('control.disabled')"
        :description="reasonText(control.options.value.reasonCode)"
        type="warning"
        :closable="false"
      />

      <template v-if="control.options.value?.canControl">
        <ElAlert v-if="control.hasInFlightOperation.value" :title="text('control.operationInProgress')" type="warning" :closable="false" />
        <div class="capability-list" :aria-label="text('control.action')">
          <div v-for="item in options" :key="item.action" class="capability-row">
            <ElTag :type="item.allowed ? 'success' : 'info'">{{ actionLabel(item.action) }}</ElTag>
            <span>{{ item.allowed ? text('normal') : reasonText(item.reasonCode) }}</span>
          </div>
        </div>

        <div v-if="allowedActions.length" class="control-form">
          <label class="form-label">
            <span>{{ text('control.action') }}</span>
            <ElSelect v-model="selectedAction" :placeholder="text('control.selectAction')">
              <ElOption
                v-for="item in options"
                :key="item.action"
                :value="item.action"
                :label="item.allowed ? actionLabel(item.action) : `${actionLabel(item.action)}（${reasonText(item.reasonCode)}）`"
                :disabled="!item.allowed"
              />
            </ElSelect>
          </label>

          <label v-if="selectedOption" class="form-label">
            <span>{{ text('control.value') }}</span>
            <ElInputNumber
              v-if="temperatureRange"
              v-model="selectedTemperature"
              :min="selectedOption.minimum ?? undefined"
              :max="selectedOption.maximum ?? undefined"
              :aria-label="text('control.value')"
              controls-position="right"
            />
            <ElSelect v-else v-model="selectedEnumValue" :placeholder="text('control.selectValue')">
              <ElOption v-for="value in selectedOption.values" :key="value" :value="value" :label="valueLabel(value)" />
            </ElSelect>
            <small v-if="temperatureRange && selectedOption.minimum != null && selectedOption.maximum != null">
              {{ text('control.temperatureRange', { minimum: selectedOption.minimum, maximum: selectedOption.maximum }) }}
            </small>
          </label>

          <ElCheckbox v-model="confirmed" :disabled="!selectedOption || !requestedValue">
            {{ text('control.submitConfirmation') }}
          </ElCheckbox>
          <div class="actions">
            <ElButton data-testid="submit-control" type="primary" :disabled="!canSubmit" :loading="control.isBusy(`submit:${equipmentId}`)" @click="submit">
              {{ text('control.submit') }}
            </ElButton>
          </div>
        </div>
        <ElEmpty v-else :description="text('control.noAction')" />
      </template>

      <section v-if="control.activeOperation.value" class="operation-result">
        <header class="section-heading">
          <h4>{{ text('control.latestResult') }}</h4>
          <ElTag :type="statusType(control.activeOperation.value.status)">{{ statusLabel(control.activeOperation.value.status) }}</ElTag>
        </header>
        <p>{{ operationMessage(control.activeOperation.value) }}</p>
        <dl class="operation-detail">
          <div><dt>{{ text('control.requestedValue') }}</dt><dd>{{ valueLabel(control.activeOperation.value.value) }}</dd></div>
          <div><dt>{{ text('control.observedValue') }}</dt><dd>{{ control.activeOperation.value.observedValue == null ? text('control.notObserved') : valueLabel(control.activeOperation.value.observedValue) }}</dd></div>
          <div><dt>{{ text('control.stableProgress') }}</dt><dd>{{ text('control.stableProgressValue', { count: control.activeOperation.value.stableCount }) }}</dd></div>
          <div><dt>{{ text('control.stableSince') }}</dt><dd>{{ date(control.activeOperation.value.stableSince) }}</dd></div>
          <div><dt>{{ text('control.lastCloudReadAt') }}</dt><dd>{{ date(control.activeOperation.value.lastReadAt) }}</dd><small>{{ text('control.cloudReadBoundary') }}</small></div>
          <div><dt>{{ text('control.unitStatus') }}</dt><dd>{{ unitStatus(control.activeOperation.value.unitStatus) }}</dd></div>
          <div><dt>{{ text('control.cloudStable') }}</dt><dd>{{ control.activeOperation.value.cloudStable ? text('control.cloudStableYes') : text('control.cloudStableNo') }}</dd></div>
          <div><dt>{{ text('control.isolation') }}</dt><dd>{{ control.activeOperation.value.isolated ? text('control.isolated') : text('control.released') }}</dd></div>
          <div><dt>{{ text('control.deadlineAt') }}</dt><dd>{{ date(control.activeOperation.value.deadlineAt) }}</dd></div>
          <div v-if="control.activeOperation.value.reasonCode"><dt>{{ text('control.reason') }}</dt><dd>{{ reasonText(control.activeOperation.value.reasonCode) }}</dd></div>
          <div v-if="control.activeOperation.value.vendorCode"><dt>{{ text('control.vendorCode') }}</dt><dd>{{ control.activeOperation.value.vendorCode }}</dd></div>
          <div><dt>{{ text('control.updatedAt') }}</dt><dd>{{ date(control.activeOperation.value.updatedAt) }}</dd></div>
        </dl>
        <div class="row-actions">
          <ElButton v-if="canHvacControlRecheck(control.activeOperation.value)" text type="primary" :loading="control.isBusy(`recheck:${control.activeOperation.value.operationId}`)" @click="control.recheck(control.activeOperation.value.operationId)">{{ text('control.recheck') }}</ElButton>
          <ElButton v-if="canHvacControlConfirm(control.activeOperation.value)" text type="warning" @click="openFieldAction(control.activeOperation.value, 'confirm')">{{ text('control.confirm') }}</ElButton>
          <ElButton v-if="canHvacControlRelease(control.activeOperation.value)" text type="warning" @click="openFieldAction(control.activeOperation.value, 'release')">{{ text('control.release') }}</ElButton>
          <ElButton v-if="canHvacControlClose(control.activeOperation.value)" text type="warning" @click="openClose(control.activeOperation.value)">{{ text('control.close') }}</ElButton>
        </div>
      </section>

      <section class="history-section">
        <h4>{{ text('control.history') }}</h4>
        <ElAlert v-if="control.historyForbidden.value" :title="text('control.historyForbidden')" type="warning" :closable="false" />
        <ElTable v-if="control.history.value.items.length" :data="control.history.value.items" row-key="operationId" size="small">
          <ElTableColumn :label="text('control.action')" min-width="100"><template #default="{ row }">{{ actionLabel(row.action) }}</template></ElTableColumn>
          <ElTableColumn :label="text('control.requestedValue')" min-width="110"><template #default="{ row }">{{ valueLabel(row.value) }}</template></ElTableColumn>
          <ElTableColumn :label="text('control.status')" min-width="180"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusLabel(row.status) }}</ElTag><p class="operation-message">{{ operationMessage(operationFromRow(row)) }}</p></template></ElTableColumn>
          <ElTableColumn :label="text('control.updatedAt')" min-width="165"><template #default="{ row }">{{ date(row.updatedAt) }}</template></ElTableColumn>
          <ElTableColumn :label="text('control.operationId')" min-width="120"><template #default="{ row }">{{ row.operationId }}</template></ElTableColumn>
          <ElTableColumn :label="text('control.action')" min-width="220" fixed="right"><template #default="{ row }"><div class="row-actions"><ElButton v-if="canHvacControlRecheck(operationFromRow(row))" text type="primary" :loading="control.isBusy(`recheck:${row.operationId}`)" @click="control.recheck(row.operationId)">{{ text('control.recheck') }}</ElButton><ElButton v-if="canHvacControlConfirm(operationFromRow(row))" text type="warning" @click="openFieldAction(operationFromRow(row), 'confirm')">{{ text('control.confirm') }}</ElButton><ElButton v-if="canHvacControlRelease(operationFromRow(row))" text type="warning" @click="openFieldAction(operationFromRow(row), 'release')">{{ text('control.release') }}</ElButton><ElButton v-if="canHvacControlClose(operationFromRow(row))" text type="warning" @click="openClose(operationFromRow(row))">{{ text('control.close') }}</ElButton></div></template></ElTableColumn>
        </ElTable>
        <ElEmpty v-else-if="!control.historyForbidden.value" :description="text('control.noHistory')" />
        <ElPagination
          v-if="control.history.value.total > control.history.value.size"
          background
          layout="total, prev, pager, next"
          :current-page="control.history.value.page"
          :page-size="control.history.value.size"
          :total="control.history.value.total"
          :disabled="control.historyLoading.value"
          @current-change="control.loadHistory"
        />
      </section>

      <section v-if="closeTarget" class="close-operation">
        <h4>{{ text('control.closeTitle') }}</h4>
        <p>{{ text('control.closeNotice') }}</p>
        <p class="close-operation-id">{{ text('control.operationId') }}{{ '：' }}{{ closeTarget.operationId }}</p>
        <ElInput v-model="closeReason" type="textarea" :rows="3" :maxlength="500" :placeholder="text('control.closeReasonPlaceholder')" :aria-label="text('control.closeReason')" />
        <small>{{ text('control.closeReasonLimit') }}</small>
        <ElCheckbox v-model="closeConfirmed" :disabled="!closeReasonValid">{{ text('control.closeConfirm') }}</ElCheckbox>
        <div class="actions">
          <ElButton type="warning" :disabled="!closeConfirmed || !closeReasonValid" :loading="control.isBusy(`close:${closeTarget.operationId}`)" @click="closeOperation">{{ text('control.closeSubmit') }}</ElButton>
        </div>
      </section>

      <section v-if="fieldTarget && fieldAction" class="field-action">
        <h4>{{ fieldAction === 'confirm' ? text('control.confirmTitle') : text('control.releaseTitle') }}</h4>
        <p>{{ fieldAction === 'confirm' ? text('control.confirmNotice') : text('control.releaseNotice') }}</p>
        <p class="close-operation-id">{{ text('control.operationId') }}{{ '：' }}{{ fieldTarget.operationId }}</p>
        <ElInput v-model="fieldReason" type="textarea" :rows="3" :maxlength="500" :placeholder="text('control.fieldReasonPlaceholder')" :aria-label="text('control.fieldReason')" />
        <small>{{ text('control.fieldReasonLimit') }}</small>
        <ElCheckbox v-model="fieldConfirmed" :disabled="!fieldReasonValid">{{ text('control.fieldConfirm') }}</ElCheckbox>
        <div class="actions">
          <ElButton type="warning" :disabled="!fieldConfirmed || !fieldReasonValid" :loading="control.isBusy(`${fieldAction}:${fieldTarget.operationId}`)" @click="submitFieldAction">{{ fieldAction === 'confirm' ? text('control.confirmSubmit') : text('control.releaseSubmit') }}</ElButton>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.control-panel, .control-form, .history-section, .operation-result, .close-operation, .field-action { display: grid; gap: var(--bec-space-group); }
.panel-heading, .section-heading, .actions, .capability-row, .row-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
.panel-heading { align-items: flex-start; }
.panel-heading h3, .section-heading h4, .history-section h4, .close-operation h4, .field-action h4, p { margin: 0; }
.panel-heading h3, .section-heading h4, .history-section h4, .close-operation h4, .field-action h4 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .operation-result > p, .close-operation > p, .field-action > p, .operation-message { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.capability-list { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.capability-row { justify-content: flex-start; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.form-label { display: grid; gap: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 8); }
.form-label > span { color: var(--bec-color-text-secondary); }
.form-label small { color: var(--bec-color-text-secondary); }
.operation-result, .close-operation, .field-action { padding: var(--bec-panel-padding); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface); }
.operation-detail { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 5), 1fr)); gap: var(--bec-space-tight); margin: 0; }
.operation-detail div { display: grid; gap: var(--bec-space-tight); }
.operation-detail dt { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.operation-detail dd { margin: 0; font-weight: var(--bec-font-weight-heading); overflow-wrap: anywhere; }
.operation-detail small { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.operation-message { margin-top: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 8); }
.row-actions { justify-content: flex-start; flex-wrap: nowrap; }
.close-operation-id { overflow-wrap: anywhere; }
.close-operation small, .field-action small { color: var(--bec-color-text-secondary); }
.actions { justify-content: flex-end; }
.el-alert { margin: 0; }
@media (max-width: 720px) { .form-label { max-width: none; } }
</style>
