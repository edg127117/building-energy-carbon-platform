<script setup lang="ts">
import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElFormItem, ElInput, ElOption, ElSelect, ElSpace, ElTag } from '@/shared/ui'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { configurationWorkOrderApi, type ConfigurationWorkOrder, type WorkOrderCommand } from '../api/configuration-work-order'
import type { ConfigurationGuideDomain, ConfigurationGuideStep } from '../api/configuration-guide'
import { localDateTimeToUtc } from '../models/classification-editor'
import { workOrderCopy as copy } from '../locales/work-order.zh-CN'
const props = defineProps<{ buildingId: string; versionId: string; revision: number; dirty: boolean }>()
const emit = defineEmits<{ navigate: [domain: ConfigurationGuideDomain]; changed: []; dirtyChange: [value: boolean] }>()
const order = ref<ConfigurationWorkOrder | null>(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
const domain = ref<ConfigurationGuideDomain | ''>(''), action = ref<WorkOrderCommand['action'] | ''>(''), reason = ref(''), effective = ref('')
const ownDirty = computed(() => !!domain.value || !!action.value || !!reason.value || !!effective.value || busy.value)
let generation = 0, request: AbortController | null = null
watch(ownDirty, value => emit('dirtyChange', value))
watch(() => [props.buildingId, props.versionId, props.revision, props.dirty], () => { generation++; request?.abort(); order.value = null; loading.value = false; busy.value = false; error.value = ''; notice.value = ''; clear() })
onBeforeUnmount(() => { generation++; request?.abort(); emit('dirtyChange', false) })
function clear() { domain.value = ''; action.value = ''; reason.value = ''; effective.value = '' }
function actions(step: ConfigurationGuideStep): WorkOrderCommand['action'][] {
  if (step.action === 'SUBMIT') return ['SUBMIT']
  if (step.action === 'REVIEW') return ['APPROVE', 'REJECT', 'WITHDRAW']
  if (step.action === 'ACTIVATE') return ['ACTIVATE']
  return []
}
function choose(step: ConfigurationGuideStep) { domain.value = step.domain; action.value = ''; reason.value = ''; effective.value = '' }
const chosen = computed(() => order.value?.guide.steps.find(s => s.domain === domain.value))
async function load() {
  if (props.dirty || busy.value || !props.buildingId || !props.versionId) return
  const owner = ++generation, controller = new AbortController(); request?.abort(); request = controller
  loading.value = true; error.value = ''; order.value = null; clear()
  try {
    const result = await configurationWorkOrderApi.get(props.buildingId, props.versionId, controller.signal)
    if (owner !== generation) return
    if (result.guide.buildingId !== props.buildingId || result.guide.versionId !== props.versionId || result.guide.revision !== props.revision) { error.value = copy.stale; return }
    order.value = result
  } catch (failure) { if (owner === generation) error.value = requestErrorMessage(failure) }
  finally { if (owner === generation) loading.value = false }
}
async function execute() {
  if (busy.value || props.dirty || !order.value || !chosen.value || !action.value || !actions(chosen.value).includes(action.value)) return
  if (!reason.value.trim()) { error.value = copy.required; return }
  let from: string | null = null
  try { from = effective.value ? localDateTimeToUtc(effective.value) : null } catch { error.value = copy.required; return }
  const owner = generation, building = props.buildingId, version = props.versionId
  const command: WorkOrderCommand = { fingerprint: order.value.fingerprint, domain: chosen.value.domain, action: action.value, reason: reason.value.trim(), effectiveFrom: from, idempotencyKey: createIdempotencyKey() }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await configurationWorkOrderApi.execute(building, version, command)
    if (owner !== generation) return
    clear(); order.value = null; notice.value = copy.success; emit('changed')
  } catch (failure) {
    if (owner === generation) { error.value = requestErrorMessage(failure); order.value = null }
  } finally { if (owner === generation) busy.value = false }
}
</script>
<template>
  <ElCard shadow="never" class="configuration-work-order">
    <template #header><h3>{{ copy.title }}</h3></template>
    <ElAlert :title="props.dirty ? copy.dirty : copy.note" type="info" :closable="false" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElButton data-test="work-load" :disabled="dirty || ownDirty || busy || loading || !buildingId || !versionId" :loading="loading" @click="load">{{ copy.load }}</ElButton>
    <ol v-if="order">
      <li v-for="step in order.guide.steps" :key="step.domain">
        <ElSpace wrap><strong>{{ t(`energyClassification.configurationGuide.domains.${step.domain}`) }}</strong><ElTag>{{ t(`energyClassification.configurationGuide.states.${step.state}`) }}</ElTag><span>{{ t(`energyClassification.configurationGuide.responsibilities.${step.responsibility}`) }}</span></ElSpace>
        <p v-for="(issue, index) in step.issues" :key="index">{{ issue.message }}</p>
        <ElSpace wrap><ElButton :disabled="busy" @click="emit('navigate', step.domain)">{{ t('energyClassification.configurationGuide.navigate') }}</ElButton><ElButton v-if="actions(step).length" :disabled="busy" @click="choose(step)">{{ copy.action }}</ElButton></ElSpace>
      </li>
    </ol>
    <ElButton v-if="ownDirty && !chosen" :disabled="busy" @click="clear">{{ copy.clear }}</ElButton>
    <p v-if="!order && !loading">{{ copy.empty }}</p>
    <div v-if="chosen" class="work-order-command">
      <ElFormItem :label="copy.action"><ElSelect v-model="action" :disabled="busy"><ElOption v-for="value in actions(chosen)" :key="value" :value="value" :label="copy.actions[value]" /></ElSelect></ElFormItem>
      <ElFormItem :label="copy.reason"><ElInput v-model="reason" :disabled="busy" maxlength="500" /></ElFormItem>
      <ElFormItem v-if="action === 'ACTIVATE' || (domain === 'CLASSIFICATION' && action === 'APPROVE')" :label="copy.effective"><input v-model="effective" type="datetime-local" :disabled="busy" /></ElFormItem>
      <ElSpace><ElButton data-test="work-execute" type="primary" :disabled="busy || dirty || !action || !reason.trim()" :loading="busy" @click="execute">{{ copy.execute }}</ElButton><ElButton :disabled="busy" @click="clear">{{ copy.clear }}</ElButton></ElSpace>
    </div>
  </ElCard>
</template>
<style scoped>
.configuration-work-order { margin-block: var(--bec-space-4); }
.configuration-work-order li { padding-block: var(--bec-space-3); }
.work-order-command { max-width: calc(var(--bec-ref-space-64) * 10); margin-block-start: var(--bec-space-group); }
</style>
