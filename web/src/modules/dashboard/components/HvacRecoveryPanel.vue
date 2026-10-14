<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElAlert, ElButton, ElInput, ElInputNumber, ElOption, ElSelect, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'
import { hvacControlApi } from '../api/hvac-control'
import type {
  HvacControlOnOff,
  HvacControlRecovery,
  HvacControlRecoveryObservation,
} from '../models/hvac-control'

const props = defineProps<{ equipmentId: string; recoveryOperationKey?: string | null }>()
const emit = defineEmits<{ recovered: [] }>()

const OBSERVE_INTERVAL_MILLIS = 30_000
const OBSERVE_WINDOW_MILLIS = 5 * 60_000
const text = (key: string, params: Record<string, string | number> = {}) => t(`dashboard.daikin.recovery.${key}`, params)

const canManage = ref<boolean | null>(null)
const testOpenControls = ref(false)
const accessLoading = ref(false)
const loading = ref(false)
const observing = ref(false)
const watching = ref(false)
const completing = ref(false)
const continuing = ref(false)
const error = ref<string | null>(null)
const recovery = shallowRef<HvacControlRecovery | null>(null)
const observation = shallowRef<HvacControlRecoveryObservation | null>(null)
const completedRecovery = ref(false)
const onOff = ref<HvacControlOnOff | null>(null)
const temperature = ref<number | undefined>(undefined)
const reason = ref(text('defaultReason'))
let generation = 0
let disposed = false
let observeStartedAt = 0
let observeTimer: ReturnType<typeof setTimeout> | undefined

const operation = computed(() => recovery.value?.operation ?? null)
const canAccess = computed(() => canManage.value === true || testOpenControls.value)
const simpleRecovery = computed(() => testOpenControls.value && recovery.value?.simpleRecovery === true)
const hasBaseline = computed(() => (onOff.value === 'on' || onOff.value === 'off')
  && Number.isInteger(temperature.value) && temperature.value! >= 16 && temperature.value! <= 32)
const observationReady = computed(() => {
  const current = observation.value
  return Boolean(current && operation.value && current.operationId === operation.value.operationId
    && current.canRecover && current.proof && current.stableCount >= 3
    && current.stableSince != null && current.lastReadAt != null
    && current.lastReadAt - current.stableSince >= 60_000)
})
const canComplete = computed(() => hasBaseline.value && observationReady.value && !observing.value && !completing.value && Boolean(reason.value.trim()))
const canContinue = computed(() => simpleRecovery.value && !continuing.value && reason.value.trim().length >= 5 && reason.value.trim().length <= 500)

function owns(owner: number, target: string): boolean {
  return !disposed && owner === generation && target === props.equipmentId
}
function stopObservation() {
  if (observeTimer) clearTimeout(observeTimer)
  observeTimer = undefined
  watching.value = false
}
function resetCandidate() {
  stopObservation()
  loading.value = false
  observing.value = false
  completing.value = false
  continuing.value = false
  error.value = null
  recovery.value = null
  observation.value = null
  completedRecovery.value = false
  onOff.value = null
  temperature.value = undefined
  reason.value = text('defaultReason')
}
function reset() {
  generation++
  canManage.value = null
  testOpenControls.value = false
  accessLoading.value = false
  resetCandidate()
}
function reasonText(reasonCode: string | null): string {
  if (!reasonCode) return text('noReason')
  const known = new Set([
    'RECOVERY_PENDING', 'RECOVERY_BASELINE_MISMATCH', 'RECOVERY_READ_UNAVAILABLE', 'RECOVERY_PROOF_EXPIRED',
    'RECOVERY_SAMPLE_INTERVAL', 'RECOVERY_NOT_ELIGIBLE', 'RECOVERY_CURRENT_BASELINE_RELEASED', 'TEST_CURRENT_STATE_RELEASED',
  ])
  return known.has(reasonCode) ? text(`reasonNames.${reasonCode}`) : text('unknownReason', { code: reasonCode })
}
function onOffLabel(value: string | null): string {
  if (value === 'on') return text('on')
  if (value === 'off') return text('off')
  return t('common.missing')
}
function date(value: number | null): string {
  return value == null ? t('common.missing') : formatDateTime(value)
}
function clearExpiredProof(reasonCode: string | null) {
  if (reasonCode !== 'RECOVERY_PROOF_EXPIRED') return
  observation.value = null
  error.value = reasonText(reasonCode)
}
async function loadRecovery(owner: number, target: string) {
  loading.value = true
  try {
    const result = await hvacControlApi.recovery(target)
    if (!owns(owner, target)) return
    if (result.operation && result.operation.equipmentId !== target) {
      recovery.value = null
      error.value = text('responseMismatch')
      return
    }
    recovery.value = result
    if (result.simpleRecovery && testOpenControls.value) reason.value = text('simpleDefaultReason')
    clearExpiredProof(result.reasonCode)
  } catch (cause) {
    if (owns(owner, target)) error.value = requestErrorMessage(cause)
  } finally {
    if (owns(owner, target)) loading.value = false
  }
}
async function load() {
  reset()
  const owner = generation
  const target = props.equipmentId
  if (!target) return
  accessLoading.value = true
  try {
    const access = await hvacControlApi.deviceGrantAccess()
    if (!owns(owner, target)) return
    canManage.value = access.canManage
    testOpenControls.value = access.testOpenControls === true
    if (canAccess.value) await loadRecovery(owner, target)
  } catch (cause) {
    if (owns(owner, target)) error.value = requestErrorMessage(cause)
  } finally {
    if (owns(owner, target)) accessLoading.value = false
  }
}
async function refreshCandidate() {
  const owner = ++generation
  const target = props.equipmentId
  resetCandidate()
  if (!target || !canAccess.value) return
  await loadRecovery(owner, target)
}
function scheduleObservation(owner: number, target: string) {
  if (!owns(owner, target) || !watching.value) return
  const remaining = observeStartedAt + OBSERVE_WINDOW_MILLIS - Date.now()
  if (remaining <= 0) {
    watching.value = false
    return
  }
  observeTimer = setTimeout(() => {
    observeTimer = undefined
    if (!owns(owner, target) || !watching.value) return
    if (Date.now() - observeStartedAt >= OBSERVE_WINDOW_MILLIS) {
      watching.value = false
      return
    }
    void observe(owner, target)
  }, Math.min(OBSERVE_INTERVAL_MILLIS, remaining))
}
async function observe(owner: number, target: string) {
  const currentOperation = operation.value
  if (!owns(owner, target) || !watching.value || observing.value || !currentOperation || !hasBaseline.value) return
  observing.value = true
  error.value = null
  try {
    const result = await hvacControlApi.observeRecovery(currentOperation.operationId, { onOff: onOff.value!, temperature: temperature.value! })
    if (!owns(owner, target) || result.operationId !== currentOperation.operationId) return
    observation.value = result
    if (result.reasonCode === 'RECOVERY_PROOF_EXPIRED') clearExpiredProof(result.reasonCode)
  } catch (cause) {
    if (owns(owner, target)) {
      error.value = requestErrorMessage(cause)
      watching.value = false
    }
  } finally {
    if (owns(owner, target)) {
      observing.value = false
      scheduleObservation(owner, target)
    }
  }
}
function startObservation() {
  const currentOperation = operation.value
  if (!currentOperation || !recovery.value?.eligible || !hasBaseline.value || observing.value || watching.value || completing.value) return
  const owner = generation
  const target = props.equipmentId
  observeStartedAt = Date.now()
  watching.value = true
  observation.value = null
  error.value = null
  void observe(owner, target)
}
async function complete() {
  const currentOperation = operation.value
  const currentObservation = observation.value
  if (!currentOperation || !currentObservation?.proof || !canComplete.value) return
  const owner = generation
  const target = props.equipmentId
  stopObservation()
  completing.value = true
  error.value = null
  try {
    const result = await hvacControlApi.completeRecovery(currentOperation.operationId, {
      onOff: onOff.value!, temperature: temperature.value!, proof: currentObservation.proof,
      reason: reason.value.trim(), onsiteConfirmed: true,
    })
    if (!owns(owner, target)) return
    recovery.value = { operation: result, eligible: false, simpleRecovery: false, reasonCode: result.reasonCode }
    observation.value = null
    completedRecovery.value = true
    emit('recovered')
  } catch (cause) {
    if (owns(owner, target)) {
      const code = requestErrorCode(cause)
      if (code === 'RECOVERY_PROOF_EXPIRED') clearExpiredProof(code)
      else error.value = requestErrorMessage(cause)
    }
  } finally {
    if (owns(owner, target)) completing.value = false
  }
}
async function continueRecovery() {
  const currentOperation = operation.value
  if (!currentOperation || !canContinue.value) return
  const owner = generation
  const target = props.equipmentId
  continuing.value = true
  error.value = null
  try {
    const result = await hvacControlApi.continueRecovery(currentOperation.operationId, { reason: reason.value.trim() })
    if (!owns(owner, target)) return
    recovery.value = { operation: result, eligible: false, simpleRecovery: false, reasonCode: result.reasonCode }
    observation.value = null
    completedRecovery.value = true
    emit('recovered')
  } catch (cause) {
    if (owns(owner, target)) error.value = requestErrorMessage(cause)
  } finally {
    if (owns(owner, target)) continuing.value = false
  }
}

// 切换抽屉设备或卸载后，旧请求和计时器都不得把上一台设备的观察结果写入当前恢复窗口。
watch(() => props.equipmentId, () => { void load() }, { immediate: true })
// 父面板只在本次操作进入未知／待核验时改变此键，避免快速回读的每次轮询都重载恢复候选。
watch(() => props.recoveryOperationKey, (next, previous) => {
  if (!next || next === previous || !canAccess.value) return
  void refreshCandidate()
})
onUnmounted(() => {
  disposed = true
  generation++
  stopObservation()
})
</script>

<template>
  <section v-if="accessLoading || loading || error || (canAccess && (recovery?.eligible || completedRecovery))" class="recovery-panel" :aria-label="text('title')">
    <header class="panel-heading">
      <div>
        <h4>{{ text('title') }}</h4>
        <p>{{ text('notice') }}</p>
      </div>
      <ElButton v-if="error && !accessLoading" text type="primary" @click="load">{{ text('refresh') }}</ElButton>
    </header>

    <ElSkeleton v-if="accessLoading || (loading && !recovery)" :rows="3" animated />
    <template v-else>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="canAccess && recovery?.eligible && operation">
        <template v-if="simpleRecovery">
          <ElAlert :title="text('simpleNotice')" :description="text('boundary')" type="info" :closable="false" />
          <dl class="detail-grid">
            <div><dt>{{ text('operationId') }}</dt><dd>{{ operation.operationId }}</dd></div>
            <div><dt>{{ text('originalTarget') }}</dt><dd>{{ operation.value }}</dd></div>
            <div><dt>{{ text('originalStatus') }}</dt><dd>{{ operation.status }}</dd></div>
            <div><dt>{{ text('isolation') }}</dt><dd>{{ operation.isolated ? text('isolated') : text('released') }}</dd></div>
          </dl>
          <label class="form-label reason-field">
            <span>{{ text('simpleReason') }}</span>
            <ElInput v-model="reason" type="textarea" :rows="3" :maxlength="500" :aria-label="text('simpleReason')" />
          </label>
          <ElButton data-testid="continue-recovery" type="warning" :disabled="!canContinue" :loading="continuing" @click="continueRecovery">{{ text('continue') }}</ElButton>
        </template>
        <template v-else>
          <ElAlert :title="text('warning')" :description="text('boundary')" type="warning" :closable="false" />
          <dl class="detail-grid">
            <div><dt>{{ text('operationId') }}</dt><dd>{{ operation.operationId }}</dd></div>
            <div><dt>{{ text('originalTarget') }}</dt><dd>{{ operation.value }}</dd></div>
            <div><dt>{{ text('originalStatus') }}</dt><dd>{{ operation.status }}</dd></div>
            <div><dt>{{ text('isolation') }}</dt><dd>{{ operation.isolated ? text('isolated') : text('released') }}</dd></div>
          </dl>

          <div class="baseline-form">
            <label class="form-label">
              <span>{{ text('currentOnOff') }}</span>
              <ElSelect v-model="onOff" data-testid="recovery-on-off" :placeholder="text('selectOnOff')" :disabled="watching || observing || completing" :aria-label="text('currentOnOff')">
                <ElOption value="on" :label="text('on')" />
                <ElOption value="off" :label="text('off')" />
              </ElSelect>
            </label>
            <label class="form-label">
              <span>{{ text('currentTemperature') }}</span>
              <ElInputNumber v-model="temperature" data-testid="recovery-temperature" :min="16" :max="32" :step="1" :precision="0" :disabled="watching || observing || completing" :aria-label="text('currentTemperature')" controls-position="right" />
            </label>
            <ElButton data-testid="observe-recovery" type="primary" :disabled="!hasBaseline || watching || observing || completing" :loading="observing" @click="startObservation">{{ text('observe') }}</ElButton>
          </div>

          <section v-if="observation" class="observation-result">
            <h5>{{ text('observationTitle') }}</h5>
            <dl class="detail-grid">
              <div><dt>{{ text('observedOnOff') }}</dt><dd>{{ onOffLabel(observation.onOff) }}</dd></div>
              <div><dt>{{ text('observedTemperature') }}</dt><dd>{{ observation.temperature == null ? t('common.missing') : `${observation.temperature} °C` }}</dd></div>
              <div><dt>{{ text('stableProgress') }}</dt><dd>{{ text('stableProgressValue', { count: observation.stableCount }) }}</dd></div>
              <div><dt>{{ text('stableSince') }}</dt><dd>{{ date(observation.stableSince) }}</dd></div>
              <div><dt>{{ text('lastReadAt') }}</dt><dd>{{ date(observation.lastReadAt) }}</dd></div>
              <div><dt>{{ text('serverEligibility') }}</dt><dd>{{ observation.canRecover ? text('canRecover') : text('cannotRecover') }}</dd></div>
            </dl>
            <p v-if="observation.reasonCode" class="reason"><span>{{ reasonText(observation.reasonCode) }}</span><small>{{ observation.reasonCode }}</small></p>
          </section>

          <label class="form-label reason-field">
            <span>{{ text('reason') }}</span>
            <ElInput v-model="reason" type="textarea" :rows="3" :maxlength="500" :aria-label="text('reason')" />
          </label>
          <p v-if="watching" class="watching">{{ text('watching') }}</p>
          <ElButton data-testid="complete-recovery" type="warning" :disabled="!canComplete" :loading="completing" @click="complete">{{ text('complete') }}</ElButton>
        </template>
      </template>
      <ElAlert v-else-if="completedRecovery && recovery?.operation" :title="reasonText(recovery.reasonCode)" :description="text('boundary')" type="success" :closable="false" />
    </template>
  </section>
</template>

<style scoped>
.recovery-panel, .baseline-form, .observation-result { display: grid; gap: var(--bec-space-group); }
.recovery-panel, .observation-result { padding: var(--bec-panel-padding); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface); }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
.panel-heading h4, .panel-heading p, .observation-result h5, .watching, .reason { margin: 0; }
.panel-heading h4, .observation-result h5 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .watching, .reason, .reason small { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 5), 1fr)); gap: var(--bec-space-tight); margin: 0; }
.detail-grid div, .form-label { display: grid; gap: var(--bec-space-tight); }
.detail-grid dt, .form-label > span { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.detail-grid dd { margin: 0; font-weight: var(--bec-font-weight-heading); overflow-wrap: anywhere; }
.baseline-form { grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: end; }
.reason-field { max-width: calc(var(--bec-control-height) * 12); }
.reason { display: grid; gap: var(--bec-space-tight); }
@media (max-width: 720px) { .baseline-form { grid-template-columns: 1fr; } .reason-field { max-width: none; } }
</style>
