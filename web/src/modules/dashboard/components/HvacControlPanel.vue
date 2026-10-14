<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElEmpty, ElInput, ElInputNumber, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { useHvacControl } from '../composables/use-hvac-control'
import HvacRecoveryPanel from './HvacRecoveryPanel.vue'
import {
  canHvacControlClose,
  canHvacControlConfirm,
  canHvacControlRecheck,
  canHvacControlRelease,
  isHvacControlFastVerification,
  isHvacControlActionValueAllowed,
  type HvacControlAction,
  type HvacControlOperation,
} from '../models/hvac-control'

const props = defineProps<{ equipmentId: string; refreshTick: number }>()
const emit = defineEmits<{ completed: [operation: HvacControlOperation] }>()
const notifiedCompletions = new Set<string>()
const text = (key: string, params: Record<string, string | number> = {}) => t(`dashboard.daikin.${key}`, params)
const control = useHvacControl(() => props.equipmentId)
const temperature = ref<number | undefined>(undefined)
const initializedTemperatureFor = ref<string | null>(null)
const secondaryValues = ref<Partial<Record<HvacControlAction, string>>>({})
const closeTarget = ref<HvacControlOperation | null>(null)
const closeReason = ref('')
const fieldTarget = ref<HvacControlOperation | null>(null)
const fieldAction = ref<'confirm' | 'release' | null>(null)
const fieldReason = ref('')

const legacyActionsVisible = computed(() => control.options.value?.testOpenControls === false)
const options = computed(() => control.options.value?.actions ?? [])
const onOffOption = computed(() => options.value.find(item => item.action === 'ON_OFF') ?? null)
const temperatureOption = computed(() => options.value.find(item => item.action === 'TEMPERATURE') ?? null)
const secondaryOptions = computed(() => options.value.filter(item => item.allowed && item.action !== 'ON_OFF' && item.action !== 'TEMPERATURE'))
const hasActions = computed(() => Boolean(onOffOption.value?.allowed || temperatureOption.value?.allowed || secondaryOptions.value.length))
const temperatureValue = computed(() => temperature.value == null ? '' : String(temperature.value))
const closeReasonValid = computed(() => {
  const length = closeReason.value.trim().length
  return length >= 5 && length <= 500
})
const fieldReasonValid = computed(() => {
  const length = fieldReason.value.trim().length
  return length >= 5 && length <= 500
})
const recoveryOperationKey = computed(() => {
  const operation = control.activeOperation.value
  if (!operation || (operation.status !== 'UNKNOWN' && operation.status !== 'UNCONFIRMED')) return null
  return `${operation.operationId}:${operation.status}:${operation.isolated ? 'isolated' : 'released'}:${control.hasInFlightOperation.value ? 'occupied' : 'available'}`
})
const operationInProgressNotice = computed(() => {
  const operation = control.activeOperation.value
  if (operation?.status === 'PREPARING' || operation?.status === 'SENDING') return operationMessage(operation)
  if (operation?.status === 'ACCEPTED') {
    if (isHvacControlFastVerification(operation) && operation.action === 'ON_OFF'
      && operation.lastReadAt != null && operation.observedValue === operation.value
      && operation.unitStatus !== (operation.value === 'on' ? 'operating' : 'stopped')) {
      return text('control.waitingRunState')
    }
    return text('control.waitingReadback')
  }
  return operation && isHvacControlFastVerification(operation)
    ? text('control.testOperationInProgress')
    : text('control.operationInProgress')
})
const blockedByOperation = computed(() => control.hasInFlightOperation.value
  || control.options.value?.reasonCode === 'BUSY' || control.options.value?.reasonCode === 'CONTROLLER_BUSY')
const blockedOperationNotice = computed(() => control.hasInFlightOperation.value
  ? operationInProgressNotice.value
  : text('control.controllerInProgress'))
const disabledNotice = computed(() => control.options.value?.testOpenControls
  ? text('control.testDisabled')
  : text('control.disabled'))

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
    TEST_WINDOW_CLOSING: 'control.reasonNames.TEST_WINDOW_CLOSING',
    PROVIDER_UNAVAILABLE: 'control.reasonNames.PROVIDER_UNAVAILABLE', PREFLIGHT_UNAVAILABLE: 'control.reasonNames.PREFLIGHT_UNAVAILABLE', PREFLIGHT_EXPIRED: 'control.reasonNames.PREFLIGHT_EXPIRED',
    SOURCE_PAUSED: 'control.reasonNames.SOURCE_PAUSED', SEND_RESULT_UNKNOWN: 'control.reasonNames.SEND_RESULT_UNKNOWN', READBACK_UNAVAILABLE: 'control.reasonNames.READBACK_UNAVAILABLE',
    READBACK_NOT_CONFIRMED: 'control.reasonNames.READBACK_NOT_CONFIRMED', READBACK_INVALID: 'control.reasonNames.READBACK_INVALID', OBSERVED_MATCH_RESULT_UNKNOWN: 'control.reasonNames.OBSERVED_MATCH_RESULT_UNKNOWN',
    CONTROLLER_BUSY: 'control.reasonNames.CONTROLLER_BUSY', ACTION_UNCONFIRMED: 'control.reasonNames.ACTION_UNCONFIRMED', CHECK_BUSY: 'control.reasonNames.CHECK_BUSY', ONSITE_CONFIRMATION_REQUIRED: 'control.reasonNames.ONSITE_CONFIRMATION_REQUIRED',
    MANUALLY_CLOSED_UNKNOWN: 'control.reasonNames.MANUALLY_CLOSED_UNKNOWN', MANUALLY_CLOSED_UNCONFIRMED: 'control.reasonNames.MANUALLY_CLOSED_UNCONFIRMED',
    CLOUD_VERIFIED_AUTO_FINISHED: 'control.reasonNames.CLOUD_VERIFIED_AUTO_FINISHED', FAST_TARGET_READBACK_MATCH: 'control.reasonNames.FAST_TARGET_READBACK_MATCH', FAST_WAITING_READBACK: 'control.reasonNames.FAST_WAITING_READBACK', FAST_TARGET_NOT_CONFIRMED: 'control.reasonNames.FAST_TARGET_NOT_CONFIRMED', RECOVERY_CURRENT_BASELINE_RELEASED: 'control.reasonNames.RECOVERY_CURRENT_BASELINE_RELEASED', TEST_CURRENT_STATE_RELEASED: 'control.reasonNames.TEST_CURRENT_STATE_RELEASED',
  }
  if (keys[reasonCode]) return text(keys[reasonCode]!)
  return reasonCode.startsWith('VENDOR_') ? text('control.vendorReason', { code: reasonCode }) : text('control.unknownReason', { code: reasonCode })
}
function statusLabel(status: HvacControlOperation['status']): string { return text(`control.statusNames.${status}`) }
function statusType(status: HvacControlOperation['status']): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'CONFIRMED' || status === 'CLOUD_VERIFIED') return 'success'
  if (status === 'OBSERVED_MATCH' || status === 'CLOSED') return 'info'
  if (status === 'REJECTED' || status === 'NOT_SENT') return 'danger'
  if (status === 'UNKNOWN' || status === 'UNCONFIRMED' || status === 'OBSERVED_STABLE') return 'warning'
  return 'primary'
}
function operationMessage(operation: HvacControlOperation): string {
  if (isHvacControlFastVerification(operation) && operation.status === 'UNKNOWN') return text('control.resultMessages.testFastUnknown')
  if (operation.status === 'UNKNOWN' && operation.cloudStable) return text('control.resultMessages.unknownCloudStable')
  if (operation.status === 'UNKNOWN' && operation.observedValue === operation.value) return text('control.resultMessages.unknownObservedMatch')
  if (operation.status === 'CLOSED' && operation.isolated) return text('control.resultMessages.closedIsolated')
  return text(`control.resultMessages.${operation.status}`)
}
function verificationPolicyLabel(operation: HvacControlOperation): string {
  return isHvacControlFastVerification(operation)
    ? text('control.verificationPolicyFast')
    : text('control.verificationPolicyLegacy')
}
function operationFromRow(row: unknown): HvacControlOperation { return row as HvacControlOperation }
function date(value: number | null): string { return value == null ? t('common.missing') : formatDateTime(value) }
function unitStatus(value: string | null): string {
  const keys: Record<string, string> = { operating: 'statusNames.operating', stopped: 'statusNames.stopped' }
  return value == null ? t('common.missing') : keys[value] ? text(keys[value]!) : value
}
function selectedSecondaryValue(action: HvacControlAction): string { return secondaryValues.value[action] ?? '' }
function setSecondaryValue(action: HvacControlAction, value: unknown) {
  secondaryValues.value = { ...secondaryValues.value, [action]: typeof value === 'string' ? value : '' }
}
function canSubmitAction(action: HvacControlAction, value: string): boolean {
  const option = options.value.find(item => item.action === action)
  return Boolean(control.options.value?.canControl && option && control.options.value.revision && isHvacControlActionValueAllowed(option, value.trim())
    && !control.hasInFlightOperation.value && !control.busy.value && !control.optionsRefreshing.value)
}
async function submitAction(action: HvacControlAction, value: string) {
  if (!canSubmitAction(action, value)) return
  await control.submit(action, value)
}
function resetDraft() {
  temperature.value = undefined
  initializedTemperatureFor.value = null
  secondaryValues.value = {}
  closeTarget.value = null
  closeReason.value = ''
  fieldTarget.value = null
  fieldAction.value = null
  fieldReason.value = ''
}
function openClose(operation: HvacControlOperation) {
  fieldTarget.value = null
  fieldAction.value = null
  closeTarget.value = operation
  closeReason.value = ''
}
function openFieldAction(operation: HvacControlOperation, action: 'confirm' | 'release') {
  closeTarget.value = null
  fieldTarget.value = operation
  fieldAction.value = action
  fieldReason.value = ''
}
async function closeOperation() {
  if (!closeTarget.value || !closeReasonValid.value) return
  const result = await control.close(closeTarget.value.operationId, closeReason.value)
  if (result) {
    closeTarget.value = null
    closeReason.value = ''
  }
}
async function submitFieldAction() {
  if (!fieldTarget.value || !fieldAction.value || !fieldReasonValid.value) return
  const result = fieldAction.value === 'confirm'
    ? await control.confirm(fieldTarget.value.operationId, fieldReason.value)
    : await control.release(fieldTarget.value.operationId, fieldReason.value)
  if (result) {
    fieldTarget.value = null
    fieldAction.value = null
    fieldReason.value = ''
  }
}
function refreshAfterRecovery() {
  void control.refresh()
}

watch(() => props.equipmentId, resetDraft)
watch(() => props.refreshTick, () => { void control.refresh() })
watch(control.activeOperation, operation => {
  if (!operation || operation.equipmentId !== props.equipmentId || operation.isolated
    || (operation.status !== 'CLOUD_VERIFIED' && operation.status !== 'CONFIRMED')
    || notifiedCompletions.has(operation.operationId)) return
  // 终态通知只触发一次权威监测查询；不把请求目标写入卡片，也不因刷新再次下发控制。
  notifiedCompletions.add(operation.operationId)
  emit('completed', operation)
})
watch(
  () => [props.equipmentId, control.options.value?.equipmentId, control.options.value?.currentTemperature] as const,
  ([equipmentId, optionEquipmentId, currentTemperature]) => {
    if (initializedTemperatureFor.value === equipmentId || optionEquipmentId !== equipmentId
      || typeof currentTemperature !== 'number' || !Number.isFinite(currentTemperature)) return
    temperature.value = currentTemperature
    initializedTemperatureFor.value = equipmentId
  },
  { immediate: true },
)
</script>

<template>
  <section class="control-panel" :aria-label="text('control.title')">
    <header class="panel-heading">
      <div>
        <h3>{{ text('control.title') }}</h3>
        <p>{{ control.options.value?.testOpenControls ? text('control.testOpenNotice') : text('control.notice') }}</p>
      </div>
      <ElButton :loading="control.refreshing.value" @click="control.refresh()">{{ text('refresh') }}</ElButton>
    </header>

    <ElSkeleton v-if="control.loading.value" :rows="4" animated />
    <template v-else>
      <ElAlert v-if="control.error.value" :title="control.error.value" type="error" :closable="false" />
      <ElAlert v-if="control.isBusy(`submit:${equipmentId}`)" data-testid="control-submit-pending" :title="text('control.pendingSubmission')" type="info" :closable="false" />
      <ElAlert v-else-if="blockedByOperation" data-testid="control-operation-pending" :title="blockedOperationNotice" :type="control.activeOperation.value?.status === 'UNKNOWN' || control.activeOperation.value?.status === 'UNCONFIRMED' ? 'warning' : 'info'" :closable="false" />
      <ElAlert v-else-if="control.options.value && !control.options.value.canControl" :title="disabledNotice" :description="reasonText(control.options.value.reasonCode)" type="warning" :closable="false" />

      <HvacRecoveryPanel :equipment-id="equipmentId" :recovery-operation-key="recoveryOperationKey" @recovered="refreshAfterRecovery" />

      <template v-if="control.options.value?.canControl">
        <ElAlert v-if="control.optionsRefreshing.value" :title="text('control.optionsRefreshing')" type="info" :closable="false" />
        <div v-if="hasActions" class="direct-controls">
          <section v-if="onOffOption" class="primary-action">
            <div class="primary-action-copy">
              <strong>{{ actionLabel('ON_OFF') }}</strong>
              <small>{{ onOffOption.allowed ? text('control.directOnOffNotice') : reasonText(onOffOption.reasonCode) }}</small>
            </div>
            <div class="actions">
              <ElButton data-testid="turn-on-control" type="primary" :disabled="!canSubmitAction('ON_OFF', 'on')" :loading="control.isBusy(`submit:${equipmentId}`)" @click="submitAction('ON_OFF', 'on')">{{ valueLabel('on') }}</ElButton>
              <ElButton data-testid="turn-off-control" :disabled="!canSubmitAction('ON_OFF', 'off')" :loading="control.isBusy(`submit:${equipmentId}`)" @click="submitAction('ON_OFF', 'off')">{{ valueLabel('off') }}</ElButton>
            </div>
          </section>

          <section v-if="temperatureOption" class="primary-action">
            <div class="primary-action-copy">
              <strong>{{ actionLabel('TEMPERATURE') }}</strong>
              <small v-if="temperatureOption.allowed && temperatureOption.minimum != null && temperatureOption.maximum != null">{{ text('control.temperatureRange', { minimum: temperatureOption.minimum, maximum: temperatureOption.maximum }) }}</small>
              <small v-else>{{ temperatureOption.allowed ? text('control.temperatureInputNotice') : reasonText(temperatureOption.reasonCode) }}</small>
            </div>
            <div class="temperature-submit">
              <ElInputNumber v-model="temperature" data-testid="temperature-control-input" :min="temperatureOption.minimum ?? undefined" :max="temperatureOption.maximum ?? undefined" :disabled="!temperatureOption.allowed || control.hasInFlightOperation.value || control.busy.value || control.optionsRefreshing.value" :aria-label="actionLabel('TEMPERATURE')" controls-position="right" />
              <ElButton data-testid="apply-temperature-control" type="primary" :disabled="!canSubmitAction('TEMPERATURE', temperatureValue)" :loading="control.isBusy(`submit:${equipmentId}`)" @click="submitAction('TEMPERATURE', temperatureValue)">{{ text('control.applyTemperature') }}</ElButton>
            </div>
          </section>

          <details v-if="secondaryOptions.length" class="secondary-controls">
            <summary>{{ text('control.secondaryTitle') }}</summary>
            <p>{{ text('control.secondaryNotice') }}</p>
            <div v-for="item in secondaryOptions" :key="item.action" class="secondary-action">
              <label class="form-label">
                <span>{{ actionLabel(item.action) }}</span>
                <ElSelect :model-value="selectedSecondaryValue(item.action)" :placeholder="text('control.selectValue')" :disabled="control.hasInFlightOperation.value || control.busy.value || control.optionsRefreshing.value" @update:model-value="setSecondaryValue(item.action, $event)">
                  <ElOption v-for="value in item.values" :key="value" :value="value" :label="valueLabel(value)" />
                </ElSelect>
              </label>
              <ElButton :disabled="!canSubmitAction(item.action, selectedSecondaryValue(item.action))" :loading="control.isBusy(`submit:${equipmentId}`)" @click="submitAction(item.action, selectedSecondaryValue(item.action))">{{ text('control.applySecondary') }}</ElButton>
            </div>
          </details>
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
          <div><dt>{{ text('control.verificationPolicy') }}</dt><dd>{{ verificationPolicyLabel(control.activeOperation.value) }}</dd></div>
          <div v-if="!isHvacControlFastVerification(control.activeOperation.value)"><dt>{{ text('control.stableProgress') }}</dt><dd>{{ text('control.stableProgressValue', { count: control.activeOperation.value.stableCount }) }}</dd></div>
          <div v-if="!isHvacControlFastVerification(control.activeOperation.value)"><dt>{{ text('control.stableSince') }}</dt><dd>{{ date(control.activeOperation.value.stableSince) }}</dd></div>
          <div><dt>{{ text('control.lastCloudReadAt') }}</dt><dd>{{ date(control.activeOperation.value.lastReadAt) }}</dd><small>{{ text('control.cloudReadBoundary') }}</small></div>
          <div><dt>{{ text('control.unitStatus') }}</dt><dd>{{ unitStatus(control.activeOperation.value.unitStatus) }}</dd></div>
          <div v-if="!isHvacControlFastVerification(control.activeOperation.value)"><dt>{{ text('control.cloudStable') }}</dt><dd>{{ control.activeOperation.value.cloudStable ? text('control.cloudStableYes') : text('control.cloudStableNo') }}</dd></div>
          <div><dt>{{ text('control.isolation') }}</dt><dd>{{ control.activeOperation.value.isolated ? text('control.isolated') : text('control.released') }}</dd></div>
          <div><dt>{{ text('control.deadlineAt') }}</dt><dd>{{ date(control.activeOperation.value.deadlineAt) }}</dd></div>
          <div v-if="control.activeOperation.value.reasonCode"><dt>{{ text('control.reason') }}</dt><dd>{{ reasonText(control.activeOperation.value.reasonCode) }}</dd></div>
          <div v-if="control.activeOperation.value.vendorCode"><dt>{{ text('control.vendorCode') }}</dt><dd>{{ control.activeOperation.value.vendorCode }}</dd></div>
          <div><dt>{{ text('control.updatedAt') }}</dt><dd>{{ date(control.activeOperation.value.updatedAt) }}</dd></div>
        </dl>
        <div class="row-actions">
          <ElButton v-if="canHvacControlRecheck(control.activeOperation.value)" text type="primary" :loading="control.isBusy(`recheck:${control.activeOperation.value.operationId}`)" @click="control.recheck(control.activeOperation.value.operationId)">{{ text('control.recheck') }}</ElButton>
          <ElButton v-if="legacyActionsVisible && canHvacControlConfirm(control.activeOperation.value)" text type="warning" @click="openFieldAction(control.activeOperation.value, 'confirm')">{{ text('control.confirm') }}</ElButton>
          <ElButton v-if="legacyActionsVisible && canHvacControlRelease(control.activeOperation.value)" text type="warning" @click="openFieldAction(control.activeOperation.value, 'release')">{{ text('control.release') }}</ElButton>
          <ElButton v-if="legacyActionsVisible && canHvacControlClose(control.activeOperation.value)" text type="warning" @click="openClose(control.activeOperation.value)">{{ text('control.close') }}</ElButton>
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
          <ElTableColumn :label="text('control.action')" min-width="220" fixed="right"><template #default="{ row }"><div class="row-actions"><ElButton v-if="canHvacControlRecheck(operationFromRow(row))" text type="primary" :loading="control.isBusy(`recheck:${row.operationId}`)" @click="control.recheck(row.operationId)">{{ text('control.recheck') }}</ElButton><ElButton v-if="legacyActionsVisible && canHvacControlConfirm(operationFromRow(row))" text type="warning" @click="openFieldAction(operationFromRow(row), 'confirm')">{{ text('control.confirm') }}</ElButton><ElButton v-if="legacyActionsVisible && canHvacControlRelease(operationFromRow(row))" text type="warning" @click="openFieldAction(operationFromRow(row), 'release')">{{ text('control.release') }}</ElButton><ElButton v-if="legacyActionsVisible && canHvacControlClose(operationFromRow(row))" text type="warning" @click="openClose(operationFromRow(row))">{{ text('control.close') }}</ElButton></div></template></ElTableColumn>
        </ElTable>
        <ElEmpty v-else-if="!control.historyForbidden.value" :description="text('control.noHistory')" />
        <ElPagination v-if="control.history.value.total > control.history.value.size" background layout="total, prev, pager, next" :current-page="control.history.value.page" :page-size="control.history.value.size" :total="control.history.value.total" :disabled="control.historyLoading.value" @current-change="control.loadHistory" />
      </section>

      <section v-if="legacyActionsVisible && closeTarget" class="close-operation">
        <h4>{{ text('control.closeTitle') }}</h4>
        <p>{{ text('control.closeNotice') }}</p>
        <p class="close-operation-id">{{ text('control.operationId') }}{{ '：' }}{{ closeTarget.operationId }}</p>
        <ElInput v-model="closeReason" type="textarea" :rows="3" :maxlength="500" :placeholder="text('control.closeReasonPlaceholder')" :aria-label="text('control.closeReason')" />
        <small>{{ text('control.closeReasonLimit') }}</small>
        <div class="actions"><ElButton type="warning" :disabled="!closeReasonValid" :loading="control.isBusy(`close:${closeTarget.operationId}`)" @click="closeOperation">{{ text('control.closeSubmit') }}</ElButton></div>
      </section>

      <section v-if="legacyActionsVisible && fieldTarget && fieldAction" class="field-action">
        <h4>{{ fieldAction === 'confirm' ? text('control.confirmTitle') : text('control.releaseTitle') }}</h4>
        <p>{{ fieldAction === 'confirm' ? text('control.confirmNotice') : text('control.releaseNotice') }}</p>
        <p class="close-operation-id">{{ text('control.operationId') }}{{ '：' }}{{ fieldTarget.operationId }}</p>
        <ElInput v-model="fieldReason" type="textarea" :rows="3" :maxlength="500" :placeholder="text('control.fieldReasonPlaceholder')" :aria-label="text('control.fieldReason')" />
        <small>{{ text('control.fieldReasonLimit') }}</small>
        <div class="actions"><ElButton type="warning" :disabled="!fieldReasonValid" :loading="control.isBusy(`${fieldAction}:${fieldTarget.operationId}`)" @click="submitFieldAction">{{ fieldAction === 'confirm' ? text('control.confirmSubmit') : text('control.releaseSubmit') }}</ElButton></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.control-panel, .direct-controls, .history-section, .operation-result, .close-operation, .field-action, .secondary-controls { display: grid; gap: var(--bec-space-group); }
.panel-heading, .section-heading, .actions, .row-actions, .primary-action, .secondary-action, .temperature-submit { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
.panel-heading { align-items: flex-start; }
.panel-heading h3, .section-heading h4, .history-section h4, .close-operation h4, .field-action h4, p { margin: 0; }
.panel-heading h3, .section-heading h4, .history-section h4, .close-operation h4, .field-action h4 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .operation-result > p, .close-operation > p, .field-action > p, .secondary-controls > p, .operation-message { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.primary-action { align-items: flex-start; padding-block: var(--bec-space-group); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.primary-action-copy { display: grid; gap: var(--bec-space-tight); }
.primary-action-copy small, .form-label > span, .close-operation small, .field-action small { color: var(--bec-color-text-secondary); }
.temperature-submit { justify-content: flex-start; }
.secondary-controls { padding: var(--bec-space-group) 0 0; }
.secondary-controls summary { cursor: pointer; font-weight: var(--bec-font-weight-heading); }
.secondary-action { align-items: flex-end; padding-top: var(--bec-space-tight); }
.form-label { display: grid; gap: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 8); }
.operation-result, .close-operation, .field-action { padding: var(--bec-panel-padding); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface); }
.operation-detail { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 5), 1fr)); gap: var(--bec-space-tight); margin: 0; }
.operation-detail div { display: grid; gap: var(--bec-space-tight); }
.operation-detail dt { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.operation-detail dd { margin: 0; font-weight: var(--bec-font-weight-heading); overflow-wrap: anywhere; }
.operation-detail small { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.operation-message { margin-top: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 8); }
.row-actions { justify-content: flex-start; flex-wrap: nowrap; }
.close-operation-id { overflow-wrap: anywhere; }
.actions { justify-content: flex-end; }
.el-alert { margin: 0; }
@media (max-width: 720px) { .form-label { max-width: none; } .primary-action, .secondary-action { align-items: stretch; } }
</style>
