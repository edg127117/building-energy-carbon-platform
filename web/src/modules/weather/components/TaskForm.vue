<template>
  <ElCard shadow="never">
    <template #header><h2>{{ kind === 'weather' ? w.weatherTask : w.energyTask }}</h2></template>
    <ElForm label-position="top">
      <template v-if="kind === 'weather'">
        <ElFormItem :label="w.source"><ElSelect v-model="source" :disabled="command.pending.value"><ElOption v-for="(label, value) in w.sourceNames" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
        <ElFormItem :label="w.product"><ElSelect v-model="product" :disabled="command.pending.value"><ElOption v-for="value in products" :key="value" :value="value" :label="w.products[value]" /></ElSelect></ElFormItem>
      </template>
      <template v-else>
        <ElAlert v-if="targets.error.value" :title="targets.error.value" type="error" :closable="false"><ElButton @click="loadTargets">{{ w.retry }}</ElButton></ElAlert>
        <ElFormItem :label="w.target"><ElSelect v-model="pointId" :loading="targets.loading.value" :disabled="command.pending.value" filterable :placeholder="w.selectTarget"><ElOption v-for="candidate in targets.data.value" :key="candidate.pointId" :value="candidate.pointId" :label="`${candidate.targetObjectName} / ${candidate.pointName || candidate.pointCode}`" /></ElSelect></ElFormItem>
        <p v-if="!targets.loading.value && !targets.error.value && !targets.data.value?.length">{{ w.noTargets }}</p>
      </template>
      <ElFormItem :label="w.range">
        <ElDatePicker v-if="history" v-model="range" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="w.start" :end-placeholder="w.end" :disabled-date="historyDateDisabled" :clearable="false" :disabled="command.pending.value" />
        <span v-else>{{ fixedRange[0] }}{{ ' — ' }}{{ fixedRange[1] }}</span>
      </ElFormItem>
      <ElAlert v-if="history && !valid" :title="w.rangeInvalid" type="warning" :closable="false" />
      <ElAlert v-if="command.message.value" :title="command.message.value" :type="command.failed.value ? 'error' : 'success'" :closable="false" />
      <ElButton type="primary" :disabled="!valid || (kind === 'energy' && !target)" :loading="command.pending.value" @click="submit">{{ w.submit }}</ElButton>
    </ElForm>
  </ElCard>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDatePicker, ElForm, ElFormItem, ElOption, ElSelect } from '@/shared/ui'
import { weatherApi } from '../api/weather'
import { useCommand } from '../composables/useCommand'
import { useResource } from '../composables/useResource'
import type { Target } from '../models/weather'
import { addDays, chinaDay, historyDateDisabled, historyRangeValid } from '../models/presentation'
import w from '../locales/zh-CN'
const props = defineProps<{ building: string; kind: 'weather' | 'energy' }>()
const emit = defineEmits<{ submitted: []; pending: [value: boolean] }>()
const command = useCommand()
const targets = useResource<Target[]>()
const source = ref('OPEN_METEO')
const product = ref('CURRENT')
const pointId = ref('')
const range = ref<string[]>([addDays(chinaDay(), -7), addDays(chinaDay(), -1)])
const today = ref(chinaDay())
const products = computed(() => source.value === 'CHINA_WEATHER' ? ['FORECAST_DAILY'] : Object.keys(w.products))
const history = computed(() => props.kind === 'energy' || product.value.startsWith('HISTORY_'))
const fixedRange = computed(() => [today.value, product.value === 'CURRENT' ? today.value : addDays(today.value, 6)])
const valid = computed(() => !history.value || historyRangeValid(range.value))
const target = computed(() => targets.data.value?.find(t => t.pointId === pointId.value))
watch(source, () => { if (!products.value.includes(product.value)) product.value = products.value[0] })
watch(command.pending, value => emit('pending', value))
function loadTargets() { const building = props.building; void targets.load(building, signal => weatherApi.targets(building, signal)) }
watch(() => props.building, () => { command.message.value = ''; pointId.value = ''; if (props.kind === 'energy') loadTargets() }, { immediate: true })
async function submit() {
  today.value = chinaDay()
  if (!valid.value) return
  const [start, end] = history.value ? range.value : fixedRange.value
  const building = props.building
  let result
  if (props.kind === 'weather') {
    const payload = { buildingId: building, source: source.value, product: product.value, start, end }
    result = await command.run('fetch', payload, key => weatherApi.fetch(payload, key))
  } else if (target.value) {
    const payload = { systemId: target.value.systemId, pointId: target.value.pointId, start, end }
    result = await command.run(`calculate:${building}`, payload, key => weatherApi.calculate(building, payload, key))
  }
  if (result) emit('submitted')
}
</script>
<style scoped>
h2 { font-size: var(--bec-font-size-title); margin: 0; }
.el-button { margin-top: var(--bec-space-group); }
.el-form-item :deep(.el-select) { width: 100%; }
</style>
