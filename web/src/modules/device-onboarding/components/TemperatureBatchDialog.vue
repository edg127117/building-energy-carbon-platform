<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import type {
  PendingDevice,
  TemperatureBatchJob,
  TemperatureBindingMode,
  TemperatureBindingOptions,
  TemperatureBindingRule,
  TemperatureInitializationPreview,
  TemperaturePlanSelection,
  TemperaturePlanView,
  TemperatureRuleDraft,
} from '../models/onboarding'

const props = withDefaults(defineProps<{
  open: boolean
  rows: PendingDevice[]
  options?: TemperatureBindingOptions | null
  optionsLoading?: boolean
  optionsError?: string | null
  plans?: TemperaturePlanView[]
  previewing?: boolean
  previewError?: string | null
  submitting?: boolean
  job?: TemperatureBatchJob | null
  jobLoading?: boolean
  jobError?: string | null
  platformAdmin?: boolean
  ruleSubmitting?: boolean
  ruleError?: string | null
  ruleResult?: { requestId: string; status: string } | null
  initializationPreview?: TemperatureInitializationPreview | null
  initializationPreviewing?: boolean
  initializationPreviewError?: string | null
  initializationSubmitting?: boolean
  initializationSubmitError?: string | null
}>(), {
  options: null,
  optionsLoading: false,
  optionsError: null,
  plans: () => [],
  previewing: false,
  previewError: null,
  submitting: false,
  job: null,
  jobLoading: false,
  jobError: null,
  platformAdmin: false,
  ruleSubmitting: false,
  ruleError: null,
  ruleResult: null,
  initializationPreview: null,
  initializationPreviewing: false,
  initializationPreviewError: null,
  initializationSubmitting: false,
  initializationSubmitError: null,
})
const emit = defineEmits<{
  close: []
  preview: [value: TemperaturePlanSelection]
  'preview-invalidated': []
  submit: [value: TemperaturePlanSelection]
  'refresh-job': []
  'retry-job': [pendingIds: string[]]
  'new-task': []
  'rule-submit': [rule: TemperatureRuleDraft]
  'initialization-preview': [templateProductId: string]
  'initialization-submit': [preview: TemperatureInitializationPreview]
}>()

const form = reactive({
  mode: 'AUTO' as Extract<TemperatureBindingMode, 'AUTO' | 'MANUAL'>,
  templateProductId: '',
  numericSourceId: '',
  existingPointIds: {} as Record<string, string>,
})
const ruleForm = reactive({
  ruleId: '',
  revision: 0,
  sourceScope: '',
  model: '',
  templateProductId: '',
  numericSourceId: '',
  enabled: true,
})
const validationKey = ref<string | null>(null)
const currentTime = ref(Date.now())
let expiryTimer: ReturnType<typeof setInterval> | null = null
const mappingPoints = ref<TemperaturePlanView['points']>([])
const selectedIds = computed(() => new Set(props.rows.map(row => row.pendingId)))
const singleDeviceSelection = computed(() => props.rows.length === 1)
const initializing = computed(() => Boolean(props.options) && props.options!.numericSources.length === 0)
const optionsUnavailable = computed(() => props.optionsLoading || !props.options)
const plans = computed(() => initializing.value
  ? (props.initializationPreview?.plans ?? []).filter(plan => selectedIds.value.has(plan.pendingId))
  : props.plans.filter(plan => selectedIds.value.has(plan.pendingId)))
const previewReady = computed(() => plans.value.length === props.rows.length
  && plans.value.every(plan => ['READY', 'COMPLETE'].includes(plan.status) && Boolean(plan.digest) && Boolean(plan.numericSourceId)))
const initializationPreviewReady = computed(() => Boolean(props.initializationPreview)
  && props.initializationPreview!.templateProductId === form.templateProductId
  && props.initializationPreview!.plans.length === props.rows.length
  && plans.value.length === props.rows.length
  && plans.value.every(plan => ['READY', 'COMPLETE'].includes(plan.status) && Boolean(plan.digest))
  && Boolean(props.initializationPreview!.digest))
const initializationPreviewExpired = computed(() => Boolean(props.initializationPreview)
  && props.initializationPreview!.expiresAt <= currentTime.value)
const retryablePendingIds = computed(() => props.job?.items
  .filter(item => (item.configurationStatus === 'FAILED' && !item.requestId)
    || (item.configurationStatus === 'PENDING' && !item.requestId)
    || item.configurationStatus === 'CACHE_PENDING')
  .map(item => item.pendingId) ?? [])
const requiresNewPreview = computed(() => props.job?.items
  .some(item => ['EXECUTION_FAILED', 'PLAN_EXPIRED'].includes(item.configurationStatus)) ?? false)
const jobMatchesRows = computed(() => {
  if (!props.job) return true
  const jobIds = new Set(props.job.items.map(item => item.pendingId))
  return jobIds.size === props.rows.length && props.rows.every(row => jobIds.has(row.pendingId))
})
const ruleBuildingId = computed(() => {
  const selectedRule = ruleForm.ruleId ? props.options?.rules.find(rule => rule.ruleId === ruleForm.ruleId) : undefined
  if (selectedRule) return selectedRule.buildingId
  const buildingIds = new Set(plans.value.map(plan => plan.buildingId).filter(Boolean))
  return buildingIds.size === 1 ? [...buildingIds][0]! : ''
})

watch(() => props.open, open => {
  if (!open) return
  Object.assign(form, { mode: 'AUTO', templateProductId: '', numericSourceId: '', existingPointIds: {} })
  resetRuleForm()
  mappingPoints.value = []
  validationKey.value = null
}, { immediate: true })

watch(() => props.options, options => {
  if (options?.numericSources.length === 0 && options.templates.length === 1 && !form.templateProductId) {
    form.templateProductId = options.templates[0]!.productId
  }
}, { immediate: true, deep: true })

watch(() => props.initializationPreview, preview => {
  if (expiryTimer) clearInterval(expiryTimer)
  expiryTimer = null
  if (preview) expiryTimer = setInterval(() => { currentTime.value = Date.now() }, 1000)
}, { immediate: true })

onUnmounted(() => {
  if (expiryTimer) clearInterval(expiryTimer)
})

watch(() => props.plans, value => {
  const first = value.find(plan => selectedIds.value.has(plan.pendingId))
  if (!first) return
  mappingPoints.value = first.points
  if (!ruleForm.ruleId && !ruleForm.templateProductId && first.templateProductId) ruleForm.templateProductId = first.templateProductId
  if (!ruleForm.ruleId && !ruleForm.numericSourceId && first.numericSourceId) ruleForm.numericSourceId = first.numericSourceId
}, { deep: true })

function invalidatePreview() {
  emit('preview-invalidated')
  validationKey.value = null
}

function changeMode() {
  form.templateProductId = ''
  form.numericSourceId = ''
  form.existingPointIds = {}
  mappingPoints.value = []
  invalidatePreview()
}

function changeTemplate() {
  form.existingPointIds = {}
  mappingPoints.value = []
  invalidatePreview()
}

function selection(): TemperaturePlanSelection {
  const selected = Object.entries(form.existingPointIds).filter(([, pointId]) => Boolean(pointId))
  return {
    mode: form.mode,
    ...(form.mode === 'MANUAL' && form.templateProductId ? { templateProductId: form.templateProductId } : {}),
    ...(form.mode === 'MANUAL' && form.numericSourceId ? { numericSourceId: form.numericSourceId } : {}),
    ...(singleDeviceSelection.value && selected.length ? { existingPointIds: Object.fromEntries(selected) } : {}),
  }
}

function preview() {
  validationKey.value = validate(false)
  if (validationKey.value) return
  emit('preview', selection())
}

function previewInitialization() {
  if (!props.rows.length) {
    validationKey.value = 'messages.temperatureBatchSelection'
    return
  }
  if (!form.templateProductId) {
    validationKey.value = 'validation.temperatureTemplate'
    return
  }
  validationKey.value = null
  emit('preview-invalidated')
  emit('initialization-preview', form.templateProductId)
}

function submit() {
  if (initializing.value) {
    if (initializationPreviewExpired.value) {
      validationKey.value = 'messages.temperatureInitializationPreviewExpired'
      return
    }
    if (!initializationPreviewReady.value) {
      validationKey.value = 'validation.temperaturePreview'
      return
    }
    if (props.initializationPreview) emit('initialization-submit', props.initializationPreview)
    return
  }
  validationKey.value = validate(true)
  if (validationKey.value) return
  emit('submit', selection())
}

function validate(requirePreview: boolean): string | null {
  if (!props.rows.length) return 'messages.temperatureBatchSelection'
  if (form.mode === 'MANUAL' && !form.templateProductId) return 'validation.temperatureTemplate'
  if (form.mode === 'MANUAL' && !form.numericSourceId) return 'validation.temperatureNumericSource'
  if (requirePreview && !previewReady.value) return 'validation.temperaturePreview'
  return null
}

function submitRule() {
  const existingRule = Boolean(ruleForm.ruleId)
  if (!ruleBuildingId.value || (ruleForm.enabled && (!ruleForm.templateProductId || !ruleForm.numericSourceId))) {
    validationKey.value = 'validation.temperatureTemplate'
    return
  }
  emit('rule-submit', {
    ...(existingRule ? { ruleId: ruleForm.ruleId } : {}),
    adapterId: 'DAIKIN_INDOOR_V2',
    buildingId: ruleBuildingId.value,
    sourceScope: ruleForm.sourceScope.trim(),
    model: ruleForm.model.trim(),
    templateProductId: ruleForm.templateProductId,
    numericSourceId: ruleForm.numericSourceId,
    revision: existingRule ? ruleForm.revision : 0,
    enabled: existingRule ? ruleForm.enabled : true,
  })
}

function resetRuleForm() {
  const first = plans.value[0]
  Object.assign(ruleForm, {
    ruleId: '',
    revision: 0,
    sourceScope: '',
    model: '',
    templateProductId: first?.templateProductId ?? '',
    numericSourceId: first?.numericSourceId ?? '',
    enabled: true,
  })
}

function selectRule(ruleId: string | undefined) {
  const rule = ruleId ? props.options?.rules.find(item => item.ruleId === ruleId) : undefined
  if (!rule) {
    resetRuleForm()
    return
  }
  Object.assign(ruleForm, {
    ruleId: rule.ruleId,
    revision: rule.revision,
    sourceScope: rule.sourceScope,
    model: rule.model,
    templateProductId: rule.templateProductId,
    numericSourceId: rule.numericSourceId,
    enabled: rule.enabled,
  })
}

function configurationStatusText(status: string) {
  const supported = ['PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'CONFIGURED', 'CACHE_PENDING', 'EXECUTION_FAILED', 'FAILED', 'PENDING', 'NOT_CONFIGURED', 'PLAN_EXPIRED']
  return supported.includes(status)
    ? t(`deviceOnboarding.temperature.configurationStatus.${status}`)
    : t('deviceOnboarding.temperature.configurationStatus.unknown')
}

function samplingStatusText(status: string) {
  const supported = ['WAITING_ACTIVATION', 'WAITING_SAMPLE', 'VALID_SAMPLE', 'WAITING_CONFIGURATION', 'PRESENT', 'MISSING', 'INVALID', 'UNCONFIRMED', 'HISTORY_UNAVAILABLE', 'QUALITY_BLOCKED']
  return supported.includes(status)
    ? t(`deviceOnboarding.temperature.samplingStatus.${status}`)
    : t('deviceOnboarding.temperature.samplingStatus.unknown')
}

function sourceLabel(sourceId: string | null) {
  if (initializing.value && props.initializationPreview) {
    return `${props.initializationPreview.sourceName} · ${props.initializationPreview.sourceId}`
  }
  if (!sourceId) return t('common.missing')
  const source = props.options?.numericSources.find(item => item.sourceId === sourceId)
  return source ? `${source.sourceName} · ${sourceId}` : sourceId
}

function ruleStatusText(status: string) {
  const supported = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'EXECUTION_FAILED']
  return supported.includes(status)
    ? t(`deviceOnboarding.temperature.ruleStatus.${status}`)
    : t('deviceOnboarding.temperature.ruleStatus.unknown')
}

function ruleLabel(rule: TemperatureBindingRule) {
  return t('deviceOnboarding.temperature.ruleOption', {
    scope: rule.sourceScope || t('deviceOnboarding.temperature.defaultSourceScope'),
    model: rule.model || t('deviceOnboarding.temperature.defaultModel'),
    revision: rule.revision,
  })
}
</script>

<template>
  <ElDialog :model-value="open" :title="t('deviceOnboarding.pending.temperatureBatch')" width="min(920px, 94vw)" @update:model-value="emit('close')">
    <section class="temperature-batch">
      <ElAlert :title="t(initializing ? 'deviceOnboarding.temperature.initializationDescription' : 'deviceOnboarding.temperature.batchDescription')" type="info" show-icon :closable="false" />
      <ElAlert v-if="optionsError" :title="optionsError" type="error" show-icon :closable="false" />
      <ElAlert v-if="initializing && !platformAdmin" :title="t('deviceOnboarding.messages.temperatureInitializationAdminRequired')" type="warning" show-icon :closable="false" />
      <ElForm v-if="initializing" label-position="top">
        <ElFormItem :label="t('deviceOnboarding.temperature.template')" required>
          <ElSelect v-model="form.templateProductId" class="wide-control" :loading="optionsLoading" :disabled="Boolean(job) || initializationSubmitting" @change="invalidatePreview"><ElOption v-for="template in options?.templates ?? []" :key="template.productId" :label="template.productName" :value="template.productId" /></ElSelect>
        </ElFormItem>
      </ElForm>
      <ElForm v-else label-position="top">
        <ElFormItem :label="t('deviceOnboarding.labels.temperatureMode')" required>
          <ElRadioGroup v-model="form.mode" @change="changeMode"><ElRadio value="AUTO">{{ t('deviceOnboarding.temperature.auto') }}</ElRadio><ElRadio value="MANUAL">{{ t('deviceOnboarding.temperature.manual') }}</ElRadio></ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="form.mode === 'MANUAL'" :label="t('deviceOnboarding.temperature.template')" required>
          <ElSelect v-model="form.templateProductId" class="wide-control" :loading="optionsLoading" @change="changeTemplate"><ElOption v-for="template in options?.templates ?? []" :key="template.productId" :label="template.productName" :value="template.productId" /></ElSelect>
        </ElFormItem>
        <ElFormItem v-if="form.mode === 'MANUAL'" :label="t('deviceOnboarding.labels.numericSource')" required>
          <ElSelect v-model="form.numericSourceId" clearable class="wide-control" :loading="optionsLoading" @change="invalidatePreview"><ElOption v-for="source in options?.numericSources ?? []" :key="source.sourceId" :label="source.sourceName" :value="source.sourceId" /></ElSelect>
        </ElFormItem>
      </ElForm>
      <div class="batch-actions"><ElButton type="primary" :disabled="optionsUnavailable || Boolean(job) || initializationSubmitting || (initializing && !platformAdmin)" :loading="initializing ? initializationPreviewing : previewing" @click="initializing ? previewInitialization() : preview()">{{ t(initializing ? 'deviceOnboarding.actions.previewTemperatureInitialization' : 'deviceOnboarding.actions.previewTemperature') }}</ElButton><span>{{ t(initializing ? 'deviceOnboarding.messages.temperatureInitializationPreviewBoundary' : 'deviceOnboarding.messages.temperaturePreviewBoundary') }}</span></div>
      <ElAlert v-if="initializing ? initializationPreviewError : previewError" :title="initializing ? initializationPreviewError! : previewError!" type="error" show-icon :closable="false" />
      <ElAlert v-if="initializing && initializationPreviewExpired" :title="t('deviceOnboarding.messages.temperatureInitializationPreviewExpired')" type="warning" show-icon :closable="false" />
      <ElTable v-if="plans.length" :data="plans" row-key="pendingId">
        <ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="pendingId" min-width="150" />
        <ElTableColumn :label="t('deviceOnboarding.labels.temperatureTemplate')" min-width="160"><template #default="{ row }">{{ row.templateName || t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.numericSource')" min-width="180"><template #default="{ row }">{{ sourceLabel(row.numericSourceId) }}</template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.configurationStatus')" min-width="120"><template #default="{ row }"><ElTag :type="row.status === 'READY' || row.status === 'COMPLETE' ? 'success' : 'warning'">{{ t(`deviceOnboarding.temperature.planStatus.${row.status}`) }}</ElTag></template></ElTableColumn>
        <ElTableColumn :label="t('deviceOnboarding.labels.temperatureMessage')" min-width="260"><template #default="{ row }">{{ row.message || t('common.missing') }}</template></ElTableColumn>
      </ElTable>
      <section v-if="!initializing && form.mode === 'MANUAL' && singleDeviceSelection && mappingPoints.length" class="reuse-points">
        <h3>{{ t('deviceOnboarding.temperature.reusePoints') }}</h3><p>{{ t('deviceOnboarding.messages.temperatureReuseHint') }}</p>
        <ElForm label-position="top"><ElFormItem v-for="point in mappingPoints" :key="point.metricCode" :label="`${point.semantic} · ${point.unit}`"><ElInput v-model="form.existingPointIds[point.metricCode]" clearable @change="invalidatePreview" /></ElFormItem></ElForm>
      </section>
      <ElAlert v-else-if="!initializing && form.mode === 'MANUAL' && !singleDeviceSelection" :title="t('deviceOnboarding.messages.temperatureBatchManualReuseBoundary')" type="info" show-icon :closable="false" />
      <ElAlert v-if="initializationSubmitError" :title="initializationSubmitError" type="error" show-icon :closable="false" />
      <ElAlert v-if="validationKey" :title="t(`deviceOnboarding.${validationKey}`)" type="error" show-icon :closable="false" />
      <section v-if="job" class="job-result">
        <div class="job-heading"><div><h3>{{ t('deviceOnboarding.pending.temperatureBatchResult') }}</h3><p>{{ job.jobId }}</p></div><ElButton :loading="jobLoading" @click="emit('refresh-job')">{{ t('deviceOnboarding.actions.refreshTemperatureBatch') }}</ElButton></div>
        <ElAlert :title="t('deviceOnboarding.messages.temperatureBatchBoundary')" type="info" show-icon :closable="false" />
        <ElAlert v-if="!jobMatchesRows" :title="t('deviceOnboarding.messages.temperatureBatchJobSelectionBoundary')" type="warning" show-icon :closable="false" />
        <ElAlert v-if="jobError" :title="jobError" type="error" show-icon :closable="false" />
        <ElTable :data="job.items" row-key="pendingId"><ElTableColumn :label="t('deviceOnboarding.labels.identity')" prop="pendingId" min-width="140" /><ElTableColumn :label="t('deviceOnboarding.labels.requestId')" min-width="160"><template #default="{ row }">{{ row.requestId || t('common.missing') }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.configurationStatus')" min-width="140"><template #default="{ row }">{{ configurationStatusText(row.configurationStatus) }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.samplingStatus')" min-width="140"><template #default="{ row }">{{ samplingStatusText(row.samplingStatus) }}</template></ElTableColumn><ElTableColumn :label="t('deviceOnboarding.labels.temperatureMessage')" min-width="240"><template #default="{ row }">{{ row.message || t('common.missing') }}</template></ElTableColumn></ElTable>
        <div class="job-actions"><ElButton v-if="retryablePendingIds.length" type="warning" :loading="jobLoading" @click="emit('retry-job', retryablePendingIds)">{{ t('deviceOnboarding.actions.retryTemperatureBatch') }}</ElButton><ElButton v-if="!jobMatchesRows || requiresNewPreview" type="warning" :disabled="jobLoading" @click="emit('new-task')">{{ t(!jobMatchesRows ? 'deviceOnboarding.actions.newTemperatureBatch' : 'deviceOnboarding.actions.restartTemperatureBatch') }}</ElButton></div>
      </section>
      <section v-if="platformAdmin && !initializing" class="rule-request">
        <h3>{{ t('deviceOnboarding.temperature.ruleTitle') }}</h3>
        <p>{{ t('deviceOnboarding.temperature.ruleDescription') }}</p>
        <ElAlert v-if="plans.length && !ruleBuildingId" :title="t('deviceOnboarding.messages.temperatureRuleBuildingBoundary')" type="warning" show-icon :closable="false" />
        <ElForm label-position="top">
          <ElFormItem :label="t('deviceOnboarding.labels.ruleSelection')">
            <ElSelect v-model="ruleForm.ruleId" clearable class="wide-control" @change="selectRule">
              <ElOption :label="t('deviceOnboarding.temperature.newRule')" value="" />
              <ElOption v-for="rule in options?.rules ?? []" :key="rule.ruleId" :label="ruleLabel(rule)" :value="rule.ruleId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="ruleForm.ruleId" :label="t('deviceOnboarding.labels.ruleRevision')">
            <ElInput :model-value="String(ruleForm.revision)" disabled />
          </ElFormItem>
          <ElFormItem :label="t('deviceOnboarding.labels.sourceScope')">
            <ElInput v-model="ruleForm.sourceScope" clearable />
          </ElFormItem>
          <ElFormItem :label="t('deviceOnboarding.labels.model')">
            <ElInput v-model="ruleForm.model" readonly />
            <p class="rule-hint">{{ t('deviceOnboarding.messages.temperatureRuleModelBoundary') }}</p>
          </ElFormItem>
          <ElFormItem :label="t('deviceOnboarding.temperature.template')" required>
            <ElSelect v-model="ruleForm.templateProductId" class="wide-control">
              <ElOption v-for="template in options?.templates ?? []" :key="template.productId" :label="template.productName" :value="template.productId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('deviceOnboarding.labels.numericSource')" required>
            <ElSelect v-model="ruleForm.numericSourceId" class="wide-control">
              <ElOption v-for="source in options?.numericSources ?? []" :key="source.sourceId" :label="source.sourceName" :value="source.sourceId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="ruleForm.ruleId" :label="t('deviceOnboarding.labels.ruleStatus')">
            <ElRadioGroup v-model="ruleForm.enabled">
              <ElRadio :value="true">{{ t('deviceOnboarding.labels.ruleEnabled') }}</ElRadio>
              <ElRadio :value="false">{{ t('deviceOnboarding.labels.ruleDisabled') }}</ElRadio>
            </ElRadioGroup>
          </ElFormItem>
        </ElForm>
        <ElAlert v-if="ruleError" :title="ruleError" type="error" show-icon :closable="false" />
        <ElAlert v-if="ruleResult" :title="`${t('deviceOnboarding.messages.temperatureRuleSubmitted')} ${ruleStatusText(ruleResult.status)}`" type="success" show-icon :closable="false" />
        <ElButton type="primary" :disabled="!ruleBuildingId" :loading="ruleSubmitting" @click="submitRule">{{ t('deviceOnboarding.actions.submitTemperatureRule') }}</ElButton>
      </section>
    </section>
    <template #footer><ElButton @click="emit('close')">{{ t('deviceOnboarding.actions.cancel') }}</ElButton><ElButton type="primary" :disabled="optionsUnavailable || Boolean(job) || (initializing ? !platformAdmin || initializationPreviewing || !initializationPreviewReady || initializationPreviewExpired : !previewReady)" :loading="initializing ? initializationSubmitting : submitting" @click="submit">{{ t(initializing ? 'deviceOnboarding.actions.submitTemperatureInitialization' : 'deviceOnboarding.actions.submitTemperatureBatch') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.temperature-batch, .reuse-points, .job-result, .rule-request { display: grid; gap: var(--bec-space-section); }
.reuse-points, .job-result, .rule-request { padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-card); }
.batch-actions, .job-heading, .job-actions { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.batch-actions span, .reuse-points p, .job-heading p, .rule-request p, .rule-hint { margin: 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.job-heading { justify-content: space-between; }
h3 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.wide-control { width: 100%; }
</style>
