<script setup lang="ts">
import { computed } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElInput, ElInputNumber, ElOption, ElSelect, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import type { ProductPointTemplate } from '@/modules/device-onboarding/public'
import type { InspectedField, PreviewMappingField, ProtocolMapping } from '../models/protocol-configuration'

const props = defineProps<{
  points: ProductPointTemplate[]
  mappings: ProtocolMapping[]
  fields: InspectedField[]
  highlightedMetric?: string | null
  highlightedField?: PreviewMappingField | null
}>()
const emit = defineEmits<{ 'update:mappings': [value: ProtocolMapping[]] }>()

const rows = computed(() => props.points.map(point => ({ point, mapping: props.mappings.find(item => item.metricCode === point.metricCode) })))

function setSource(point: ProductPointTemplate, path: string) {
  // JSON 数值无法证明物理单位；新映射留空交由实施人员确认，改路径保留已有换算与启停状态。
  const previous = props.mappings.find(item => item.metricCode === point.metricCode)
  const remaining = props.mappings.filter(item => item.metricCode !== point.metricCode)
  if (path) {
    remaining.push(previous ? { ...previous, sourcePath: path } : {
      metricCode: point.metricCode,
      sourcePath: path,
      sourceUnit: '',
      targetUnit: point.unit,
      scale: '1',
      offset: '0',
      required: point.required,
      enabled: true,
      sortOrder: point.sortOrder,
    })
  }
  emit('update:mappings', remaining)
}

function issue(point: ProductPointTemplate, mapping?: ProtocolMapping) {
  if (!mapping || !mapping.enabled) return point.required ? 'requiredMissing' : 'optionalMissing'
  const sourcePath = (mapping.sourcePath ?? '').trim()
  if (!sourcePath) return point.required ? 'requiredMissing' : 'optionalMissing'
  if (!(mapping.sourceUnit ?? '').trim()) return 'unitMissing'
  if (props.fields.length && props.fields.find(field => field.path === sourcePath)?.type !== 'NUMBER') return 'numberRequired'
  return 'mapped'
}

function isFieldHighlighted(metricCode: string, field: PreviewMappingField) {
  return props.highlightedMetric === metricCode && props.highlightedField === field
}

function advancedSummary(mapping: ProtocolMapping) {
  const scaleText = (mapping.scale ?? '').trim() || '—'
  const offsetText = (mapping.offset ?? '').trim() || '—'
  const parts = [
    t('protocolConfiguration.flow.advanced'),
    `${t('protocolConfiguration.labels.scale')} ${scaleText}`,
    `${t('protocolConfiguration.labels.offset')} ${offsetText}`,
  ]
  if (!mapping.enabled) parts.push(t('protocolConfiguration.flow.disabledMapping'))
  return parts.join(' · ')
}
</script>

<template>
  <div class="mapping-list">
    <section
      v-for="{ point, mapping } in rows"
      :id="`metric-${point.metricCode}`"
      :key="point.metricCode"
      class="mapping-row"
      :class="{ 'mapping-row--highlighted': highlightedMetric === point.metricCode }"
    >
      <header>
        <div class="metric-title">
          <strong>{{ point.pointNameTemplate }}</strong>
          <ElTag size="small" :type="point.required ? 'warning' : 'info'">
            {{ t(point.required ? 'protocolConfiguration.flow.requiredTag' : 'protocolConfiguration.flow.optionalTag') }}
          </ElTag>
        </div>
        <ElTag :type="issue(point, mapping) === 'mapped' ? 'success' : point.required ? 'warning' : 'info'">
          {{ t(`protocolConfiguration.flow.${issue(point, mapping)}`) }}
        </ElTag>
      </header>
      <div class="mapping-fields">
        <label :class="{ 'field-highlight': isFieldHighlighted(point.metricCode, 'sourcePath') }">
          <span>{{ t('protocolConfiguration.labels.sourcePath') }}</span>
          <ElSelect
            :model-value="mapping?.sourcePath ?? ''"
            filterable
            clearable
            :aria-label="`${point.pointNameTemplate} ${t('protocolConfiguration.labels.sourcePath')}`"
            @change="path => setSource(point, path)"
          >
            <ElOption
              v-if="mapping && (mapping.sourcePath ?? '').trim() && !fields.some(field => field.path === mapping.sourcePath)"
              :value="mapping.sourcePath"
              :label="mapping.sourcePath"
            />
            <ElOption
              v-for="field in fields"
              :key="field.path"
              :value="field.path"
              :disabled="field.type !== 'NUMBER'"
              :label="`${field.path} · ${field.value}`"
            />
          </ElSelect>
        </label>
        <label>
          <span>{{ t('protocolConfiguration.labels.sampleValue') }}</span>
          <span class="readonly-value">{{ fields.find(field => field.path === (mapping?.sourcePath ?? ''))?.value ?? '—' }}</span>
        </label>
        <label :class="{ 'field-highlight': isFieldHighlighted(point.metricCode, 'sourceUnit') }">
          <span>{{ t('protocolConfiguration.labels.sourceUnit') }}</span>
          <ElInput
            v-if="mapping"
            v-model="mapping.sourceUnit"
            :aria-label="`${point.pointNameTemplate} ${t('protocolConfiguration.labels.sourceUnit')}`"
            :placeholder="t('protocolConfiguration.placeholders.sourceUnit')"
            maxlength="20"
          />
          <span v-else class="readonly-value">{{ '—' }}</span>
        </label>
        <label>
          <span>{{ t('protocolConfiguration.labels.targetUnit') }}</span>
          <span class="readonly-value">{{ point.unit }}</span>
        </label>
      </div>
      <details v-if="mapping">
        <summary>{{ advancedSummary(mapping) }}</summary>
        <div class="mapping-fields">
          <label :class="{ 'field-highlight': isFieldHighlighted(point.metricCode, 'scale') }">
            <span>{{ t('protocolConfiguration.labels.scale') }}</span>
            <ElInput v-model="mapping.scale" :aria-label="t('protocolConfiguration.labels.scale')" maxlength="40" />
          </label>
          <label :class="{ 'field-highlight': isFieldHighlighted(point.metricCode, 'offset') }">
            <span>{{ t('protocolConfiguration.labels.offset') }}</span>
            <ElInput v-model="mapping.offset" :aria-label="t('protocolConfiguration.labels.offset')" maxlength="40" />
          </label>
          <label>
            <span>{{ t('protocolConfiguration.labels.sortOrder') }}</span>
            <ElInputNumber v-model="mapping.sortOrder" :min="0" />
          </label>
          <div class="advanced-toggles">
            <ElCheckbox v-model="mapping.enabled">{{ t('protocolConfiguration.labels.enabled') }}</ElCheckbox>
            <ElCheckbox v-model="mapping.required" :disabled="point.required">{{ t('protocolConfiguration.labels.required') }}</ElCheckbox>
            <ElButton link type="danger" @click="setSource(point, '')">{{ t('protocolConfiguration.actions.removeMapping') }}</ElButton>
          </div>
        </div>
      </details>
    </section>
    <ElAlert v-if="!points.length" :title="t('protocolConfiguration.states.mappingEmpty')" type="info" :closable="false" />
  </div>
</template>

<style scoped>
.mapping-list { display: grid; gap: var(--bec-space-group); container-type: inline-size; }
.mapping-row {
  padding: var(--bec-space-group);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
  background: var(--bec-color-surface);
  scroll-margin-top: calc(var(--bec-space-section) * 4);
}
.mapping-row--highlighted {
  border-color: var(--bec-color-error);
  background: color-mix(in srgb, var(--bec-color-error) 6%, var(--bec-color-surface));
}
header { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-group); margin-bottom: var(--bec-space-group); flex-wrap: wrap; }
.metric-title { display: flex; align-items: center; gap: var(--bec-space-tight); min-width: 0; }
.mapping-fields { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr); gap: var(--bec-space-group); align-items: start; }
label { display: grid; gap: var(--bec-space-tight); min-width: 0; color: var(--bec-color-text-secondary); }
.readonly-value { color: var(--bec-color-text-primary); overflow-wrap: anywhere; line-height: var(--bec-line-height); padding-block: var(--bec-focus-offset); }
.field-highlight > span:first-child { color: var(--bec-color-error); font-weight: var(--bec-font-weight-heading); }
.advanced-toggles { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); padding-top: var(--bec-space-group); }
summary { cursor: pointer; margin-top: var(--bec-space-group); color: var(--bec-color-text-secondary); }
@container (max-width: 560px) { .mapping-fields { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } }
@media (max-width: 760px) { .mapping-fields { grid-template-columns: 1fr 1fr; } }
@media (max-width: 480px) { .mapping-fields { grid-template-columns: 1fr; } }
</style>
