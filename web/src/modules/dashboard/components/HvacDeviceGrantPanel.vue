<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElEmpty, ElInput, ElOption,
  ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { hvacControlApi } from '../api/hvac-control'
import type { DaikinDevice } from '../models/daikin'
import type {
  HvacControlDeviceGrantBatchItem,
  HvacControlGrantAction,
} from '../models/hvac-control'

type GrantScope = 'FILTERED' | 'SPACE' | 'SYSTEM'
type SpaceOption = { value: string; label: string; spaceIds: string[] }
type ExcludedDevice = { device: DaikinDevice; reason: 'NOT_INDOOR' | 'INACTIVE' | 'EXCEPTION' | 'STALE' }

const props = defineProps<{
  buildingName: string
  devices: DaikinDevice[]
  spaceOptions: SpaceOption[]
}>()

const BATCH_SIZE = 10
const MAX_DEVICE_COUNT = 100
const text = (key: string, params: Record<string, string | number> = {}) => t(`dashboard.daikin.deviceGrant.${key}`, params)
const canManage = ref<boolean | null>(null)
const accessLoading = ref(false)
const accessError = ref<string | null>(null)
const scope = ref<GrantScope>('FILTERED')
const scopeValue = ref('')
const actions = ref<HvacControlGrantAction[]>(['ON_OFF', 'TEMPERATURE'])
const evidence = ref(text('defaultEvidence'))
const processing = ref(false)
const resultItems = ref<HvacControlDeviceGrantBatchItem[]>([])
const unknownEquipmentIds = ref<string[]>([])
const unprocessedEquipmentIds = ref<string[]>([])
const batchError = ref<string | null>(null)

const spaceScopeOptions = computed(() => props.spaceOptions.filter(option => {
  const ids = new Set(option.spaceIds)
  return props.devices.some(device => device.spaceId != null && ids.has(device.spaceId))
}))
const systemScopeOptions = computed(() => Array.from(new Set(
  props.devices.map(device => device.systemGroupId).filter((value): value is string => Boolean(value)),
)).sort((a, b) => a.localeCompare(b, 'zh-CN', { numeric: true })))
const scopedDevices = computed(() => {
  if (scope.value === 'SPACE') {
    const option = props.spaceOptions.find(item => item.value === scopeValue.value)
    if (!option) return []
    const ids = new Set(option.spaceIds)
    return props.devices.filter(device => device.spaceId != null && ids.has(device.spaceId))
  }
  if (scope.value === 'SYSTEM') {
    if (!scopeValue.value) return []
    return props.devices.filter(device => device.systemGroupId === scopeValue.value)
  }
  return props.devices
})
const selectedDevices = computed(() => {
  const seen = new Set<string>()
  return scopedDevices.value.filter(device => {
    if (seen.has(device.equipmentId)) return false
    seen.add(device.equipmentId)
    return true
  })
})
const excludedDevices = computed<ExcludedDevice[]>(() => selectedDevices.value.flatMap(device => {
  const reason = exclusionReason(device)
  return reason ? [{ device, reason }] : []
}))
const equipmentIds = computed(() => selectedDevices.value
  .filter(device => !exclusionReason(device))
  .map(device => device.equipmentId))
const evidenceValid = computed(() => {
  const length = evidence.value.trim().length
  return length >= 5 && length <= 500
})
const tooManyDevices = computed(() => equipmentIds.value.length > MAX_DEVICE_COUNT)
const canSubmit = computed(() => Boolean(
  canManage.value
  && equipmentIds.value.length > 0
  && !tooManyDevices.value
  && actions.value.length > 0
  && evidenceValid.value
  && !processing.value,
))
const processedCount = computed(() => resultItems.value.length)
const deviceNames = computed(() => new Map(props.devices.map(device => [device.equipmentId, device.equipmentName || device.equipmentCode || device.equipmentId])))

function exclusionReason(device: DaikinDevice): ExcludedDevice['reason'] | null {
  // 前端只按已展示的监测状态排除风险设备；服务端仍是权限和关系的最终裁决者。
  if (device.deviceKind !== 'INDOOR') return 'NOT_INDOOR'
  if (!device.active) return 'INACTIVE'
  if (device.hasActiveException) return 'EXCEPTION'
  if (device.stale) return 'STALE'
  return null
}
function exclusionLabel(reason: ExcludedDevice['reason']): string {
  return text(`exclusion.${reason}`)
}
function deviceLabel(equipmentId: string): string {
  return deviceNames.value.get(equipmentId) ?? equipmentId
}
function grantReasonText(reasonCode: string | null): string {
  if (!reasonCode) return t('common.missing')
  const known = new Set([
    'GRANT_UNAVAILABLE', 'CONTROLLER_BUSY', 'PREFLIGHT_EXPIRED', 'REVISION_CHANGED',
    'TARGET_UNAVAILABLE', 'LINKED_DEVICE_UNCONFIRMED',
  ])
  return known.has(reasonCode) ? text(`reasonNames.${reasonCode}`) : text('unknownReason', { code: reasonCode })
}
function chunks<T>(items: T[], size: number): T[][] {
  const result: T[][] = []
  for (let index = 0; index < items.length; index += size) result.push(items.slice(index, index + size))
  return result
}
function resetOutcome() {
  resultItems.value = []
  unknownEquipmentIds.value = []
  unprocessedEquipmentIds.value = []
  batchError.value = null
}
async function loadAccess() {
  accessLoading.value = true
  accessError.value = null
  try {
    canManage.value = (await hvacControlApi.deviceGrantAccess()).canManage
  } catch (reason) {
    canManage.value = null
    accessError.value = requestErrorMessage(reason)
  } finally {
    accessLoading.value = false
  }
}
async function submit() {
  const ids = equipmentIds.value
  const selectedActions = [...actions.value]
  const normalizedEvidence = evidence.value.trim()
  if (!canSubmit.value) return

  processing.value = true
  resetOutcome()
  const batches = chunks(ids, BATCH_SIZE)
  for (let index = 0; index < batches.length; index++) {
    const batch = batches[index]!
    try {
      const response = await hvacControlApi.grantDevices({ equipmentIds: batch, actions: selectedActions, evidence: normalizedEvidence })
      const returned = new Map(response.items
        .filter(item => batch.includes(item.equipmentId))
        .map(item => [item.equipmentId, item]))
      resultItems.value = [...resultItems.value, ...returned.values()]
      const missing = batch.filter(equipmentId => !returned.has(equipmentId))
      if (missing.length) unknownEquipmentIds.value = [...unknownEquipmentIds.value, ...missing]
    } catch (reason) {
      batchError.value = requestErrorMessage(reason)
      unknownEquipmentIds.value = [...unknownEquipmentIds.value, ...batch]
      unprocessedEquipmentIds.value = batches.slice(index + 1).flat()
      break
    }
  }
  processing.value = false
}

watch(scope, () => {
  scopeValue.value = ''
  resetOutcome()
})
watch(() => props.devices, resetOutcome)
onMounted(() => { void loadAccess() })
</script>

<template>
  <section class="device-grant-panel" :aria-label="text('title')">
    <header class="panel-heading">
      <div>
        <h2>{{ text('title') }}</h2>
        <p>{{ text('notice') }}</p>
      </div>
      <ElButton :loading="accessLoading" @click="loadAccess">{{ text('refreshAccess') }}</ElButton>
    </header>

    <ElSkeleton v-if="accessLoading && canManage == null" :rows="3" animated />
    <template v-else>
      <ElAlert v-if="accessError" :title="accessError" type="error" :closable="false" />
      <ElAlert v-else-if="canManage === false" :title="text('forbidden')" type="warning" :closable="false" />
      <template v-else-if="canManage">
        <div class="grant-form">
          <label class="form-label">
            <span>{{ text('scope') }}</span>
            <ElSelect v-model="scope" :aria-label="text('scope')">
              <ElOption value="FILTERED" :label="text('scopeFiltered')" />
              <ElOption value="SPACE" :label="text('scopeSpace')" />
              <ElOption value="SYSTEM" :label="text('scopeSystem')" />
            </ElSelect>
          </label>
          <label v-if="scope === 'SPACE'" class="form-label">
            <span>{{ text('space') }}</span>
            <ElSelect v-model="scopeValue" :placeholder="text('selectSpace')" :aria-label="text('space')">
              <ElOption v-for="option in spaceScopeOptions" :key="option.value" :value="option.value" :label="option.label" />
            </ElSelect>
          </label>
          <label v-if="scope === 'SYSTEM'" class="form-label">
            <span>{{ text('system') }}</span>
            <ElSelect v-model="scopeValue" :placeholder="text('selectSystem')" :aria-label="text('system')">
              <ElOption v-for="option in systemScopeOptions" :key="option" :value="option" :label="option" />
            </ElSelect>
          </label>
          <p class="scope-summary">{{ text('scopeSummary', { building: buildingName, count: selectedDevices.length, eligible: equipmentIds.length }) }}</p>

          <ElAlert v-if="tooManyDevices" :title="text('tooMany', { count: equipmentIds.length, max: MAX_DEVICE_COUNT })" type="error" :closable="false" />
          <ElAlert v-else-if="selectedDevices.length && !equipmentIds.length" :title="text('noEligible')" type="warning" :closable="false" />

          <section v-if="excludedDevices.length" class="exception-list">
            <h3>{{ text('exceptionTitle', { count: excludedDevices.length }) }}</h3>
            <p>{{ text('exceptionNotice') }}</p>
            <ul>
              <li v-for="item in excludedDevices" :key="item.device.equipmentId">
                <strong>{{ item.device.equipmentName || item.device.equipmentCode || item.device.equipmentId }}</strong>
                <span>{{ exclusionLabel(item.reason) }}</span>
              </li>
            </ul>
          </section>

          <fieldset class="action-selection">
            <legend>{{ text('actions') }}</legend>
            <ElCheckboxGroup v-model="actions">
              <ElCheckbox value="ON_OFF">{{ text('actionOnOff') }}</ElCheckbox>
              <ElCheckbox value="TEMPERATURE">{{ text('actionTemperature') }}</ElCheckbox>
            </ElCheckboxGroup>
          </fieldset>

          <label class="form-label evidence-field">
            <span>{{ text('evidence') }}</span>
            <ElInput v-model="evidence" type="textarea" :rows="3" :maxlength="500" :placeholder="text('evidencePlaceholder')" :aria-label="text('evidence')" />
            <small>{{ text('evidenceLimit') }}</small>
          </label>

          <div class="submit-row">
            <p v-if="processing">{{ text('processing', { returned: processedCount, total: equipmentIds.length }) }}</p>
            <ElButton data-testid="submit-device-grants" type="primary" :disabled="!canSubmit" :loading="processing" @click="submit">{{ text('submit', { count: equipmentIds.length }) }}</ElButton>
          </div>
        </div>

        <ElEmpty v-if="!selectedDevices.length" :description="text('emptyScope')" />
        <ElAlert v-if="batchError" :title="text('interrupted')" :description="text('interruptedDescription', { error: batchError })" type="error" :closable="false" />
        <ElAlert v-if="unknownEquipmentIds.length" :title="text('unknownTitle', { count: unknownEquipmentIds.length })" :description="text('unknownDescription')" type="warning" :closable="false" />
        <ul v-if="unknownEquipmentIds.length" class="unresolved-list"><li v-for="equipmentId in unknownEquipmentIds" :key="equipmentId">{{ deviceLabel(equipmentId) }}</li></ul>
        <ElAlert v-if="unprocessedEquipmentIds.length" :title="text('unprocessedTitle', { count: unprocessedEquipmentIds.length })" :description="text('unprocessedDescription')" type="info" :closable="false" />
        <ul v-if="unprocessedEquipmentIds.length" class="unresolved-list"><li v-for="equipmentId in unprocessedEquipmentIds" :key="equipmentId">{{ deviceLabel(equipmentId) }}</li></ul>

        <section v-if="resultItems.length" class="result-section">
          <h3>{{ text('resultTitle', { count: resultItems.length }) }}</h3>
          <ElTable :data="resultItems" row-key="equipmentId" size="small">
            <ElTableColumn :label="text('resultEquipment')" min-width="180"><template #default="{ row }">{{ deviceLabel(row.equipmentId) }}</template></ElTableColumn>
            <ElTableColumn :label="text('resultStatus')" min-width="110"><template #default="{ row }"><ElTag :type="row.opened ? 'success' : 'warning'">{{ row.opened ? text('opened') : text('notOpened') }}</ElTag></template></ElTableColumn>
            <ElTableColumn :label="text('resultReason')" min-width="200"><template #default="{ row }"><span>{{ grantReasonText(row.reasonCode) }}</span><small v-if="row.reasonCode" class="reason-code">{{ row.reasonCode }}</small></template></ElTableColumn>
            <ElTableColumn :label="text('resultVersion')" min-width="100"><template #default="{ row }">{{ row.version ?? t('common.missing') }}</template></ElTableColumn>
          </ElTable>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.device-grant-panel, .grant-form, .exception-list, .result-section { display: grid; gap: var(--bec-space-group); }
.device-grant-panel { padding: var(--bec-panel-padding); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface); }
.panel-heading, .submit-row { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .exception-list h3, .result-section h3, .panel-heading p, .scope-summary, .exception-list p, .submit-row p { margin: 0; }
.panel-heading h2, .exception-list h3, .result-section h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .scope-summary, .exception-list p, .submit-row p, .evidence-field small { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.form-label { display: grid; gap: var(--bec-space-tight); max-width: calc(var(--bec-control-height) * 10); }
.form-label > span, .action-selection legend { color: var(--bec-color-text-secondary); }
.action-selection { display: grid; gap: var(--bec-space-tight); padding: 0; border: 0; }
.exception-list ul, .unresolved-list { display: grid; gap: var(--bec-space-tight); margin: 0; padding-left: var(--bec-panel-padding); }
.exception-list li { display: flex; gap: var(--bec-space-tight); flex-wrap: wrap; }
.exception-list li span { color: var(--bec-color-text-secondary); }
.submit-row { align-items: center; }
.reason-code { display: block; margin-top: var(--bec-space-tight); color: var(--bec-color-text-secondary); overflow-wrap: anywhere; }
@media (max-width: 720px) { .form-label { max-width: none; } }
</style>
