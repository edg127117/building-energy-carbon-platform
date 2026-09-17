<script setup lang="ts">
import { computed } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElInput, ElInputNumber, ElOption, ElSelect, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import type { ProductPointTemplate } from '@/modules/device-onboarding/public'
import type { InspectedField, ProtocolMapping } from '../models/protocol-configuration'

const props = defineProps<{ points: ProductPointTemplate[]; mappings: ProtocolMapping[]; fields: InspectedField[] }>()
const emit = defineEmits<{ 'update:mappings': [value: ProtocolMapping[]] }>()
const rows = computed(() => props.points.map(point => ({ point, mapping: props.mappings.find(item => item.metricCode === point.metricCode) })))
function setSource(point: ProductPointTemplate, path: string) {
  // JSON 数值无法证明物理单位；新映射留空交由实施人员确认，改路径保留已有换算与启停状态。
  const previous = props.mappings.find(item => item.metricCode === point.metricCode)
  const remaining = props.mappings.filter(item => item.metricCode !== point.metricCode)
  if (path) remaining.push(previous ? { ...previous, sourcePath: path } : {
    metricCode: point.metricCode, sourcePath: path, sourceUnit: '', targetUnit: point.unit,
    scale: '1', offset: '0', required: point.required, enabled: true, sortOrder: point.sortOrder,
  })
  emit('update:mappings', remaining)
}
function issue(point: ProductPointTemplate, mapping?: ProtocolMapping) {
  if (!mapping || !mapping.enabled) return point.required ? 'requiredMissing' : 'optionalMissing'
  if (!mapping.sourceUnit.trim()) return 'unitMissing'
  if (props.fields.length && props.fields.find(field => field.path === mapping.sourcePath)?.type !== 'NUMBER') return 'numberRequired'
  return 'mapped'
}
</script>

<template>
  <div class="mapping-list">
    <section v-for="{ point, mapping } in rows" :id="`metric-${point.metricCode}`" :key="point.metricCode" class="mapping-row">
      <header><strong>{{ point.pointNameTemplate }}</strong><ElTag :type="issue(point, mapping) === 'mapped' ? 'success' : point.required ? 'warning' : 'info'">{{ t(`protocolConfiguration.flow.${issue(point, mapping)}`) }}</ElTag></header>
      <div class="mapping-fields">
        <label>{{ t('protocolConfiguration.labels.sourcePath') }}
          <ElSelect :model-value="mapping?.sourcePath ?? ''" filterable clearable :aria-label="`${point.pointNameTemplate} ${t('protocolConfiguration.labels.sourcePath')}`" @change="path => setSource(point, path)">
            <ElOption v-if="mapping && !fields.some(field => field.path === mapping.sourcePath)" :value="mapping.sourcePath" :label="mapping.sourcePath" />
            <ElOption v-for="field in fields" :key="field.path" :value="field.path" :disabled="field.type !== 'NUMBER'" :label="`${field.path} · ${field.value}`" />
          </ElSelect>
        </label>
        <label>{{ t('protocolConfiguration.labels.sampleValue') }}<span>{{ fields.find(field => field.path === mapping?.sourcePath)?.value ?? '—' }}</span></label>
        <label>{{ t('protocolConfiguration.labels.sourceUnit') }}<ElInput v-if="mapping" v-model="mapping.sourceUnit" :aria-label="`${point.pointNameTemplate} ${t('protocolConfiguration.labels.sourceUnit')}`" :placeholder="t('protocolConfiguration.placeholders.sourceUnit')" maxlength="20" /><span v-else>{{ "—" }}</span></label>
        <label>{{ t('protocolConfiguration.labels.targetUnit') }}<span>{{ point.unit }}</span></label>
      </div>
      <details v-if="mapping">
        <summary>{{ t('protocolConfiguration.flow.advanced') }}{{ "·" }}{{ t('protocolConfiguration.labels.scale') }} {{ mapping.scale }}{{ "·" }}{{ t('protocolConfiguration.labels.offset') }} {{ mapping.offset }} <span v-if="!mapping.enabled">{{ "·" }}{{ t('protocolConfiguration.flow.disabledMapping') }}</span></summary>
        <div class="mapping-fields">
          <label>{{ t('protocolConfiguration.labels.scale') }}<ElInput v-model="mapping.scale" :aria-label="t('protocolConfiguration.labels.scale')" maxlength="40" /></label>
          <label>{{ t('protocolConfiguration.labels.offset') }}<ElInput v-model="mapping.offset" :aria-label="t('protocolConfiguration.labels.offset')" maxlength="40" /></label>
          <label>{{ t('protocolConfiguration.labels.sortOrder') }}<ElInputNumber v-model="mapping.sortOrder" :min="0" /></label>
          <div><ElCheckbox v-model="mapping.enabled">{{ t('protocolConfiguration.labels.enabled') }}</ElCheckbox><ElCheckbox v-model="mapping.required" :disabled="point.required">{{ t('protocolConfiguration.labels.required') }}</ElCheckbox><ElButton link type="danger" @click="setSource(point, '')">{{ t('protocolConfiguration.actions.removeMapping') }}</ElButton></div>
        </div>
      </details>
    </section>
    <ElAlert v-if="!points.length" :title="t('protocolConfiguration.validation.product')" type="info" :closable="false" />
  </div>
</template>

<style scoped>
.mapping-list { display: grid; gap: var(--bec-space-section); container-type: inline-size; }
.mapping-row { padding-block: var(--bec-space-group); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); scroll-margin-top: calc(var(--bec-space-section) * 4); }
header { display: flex; justify-content: space-between; gap: var(--bec-space-group); margin-bottom: var(--bec-space-group); }
.mapping-fields { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr); gap: var(--bec-space-group); }
label { display: grid; gap: var(--bec-space-tight); min-width: 0; }
label > span { overflow-wrap: anywhere; }
summary { cursor: pointer; margin-block: var(--bec-space-group); color: var(--bec-color-text-secondary); }
@container (max-width: 560px) { .mapping-fields { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } }
@media (max-width: 760px) { .mapping-fields { grid-template-columns: 1fr 1fr; } }
@media (max-width: 480px) { .mapping-fields { grid-template-columns: 1fr; } }
</style>
