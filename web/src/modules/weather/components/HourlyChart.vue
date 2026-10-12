<template>
  <ElCard shadow="never">
    <template #header><div class="heading"><h2>{{ title }}</h2><ElButton text @click="detailsOpen = true">{{ w.details }}</ElButton></div></template>
    <div class="heading">
      <slot name="date-controls"><span>{{ start }}{{ ' — ' }}{{ end }}</span></slot>
      <ElSelect v-model="metric" :aria-label="w.product"><ElOption v-for="m in metrics" :key="m.key" :value="m.key" :label="m.label" /></ElSelect>
    </div>
    <ElAlert v-if="resource.error.value" :title="resource.error.value" type="error" :closable="false" />
    <p v-if="statuses" class="hint">{{ statuses }}</p>
    <div class="chart"><ChartView :option="option" :loading="resource.loading.value" :empty="!resource.data.value?.some(s => s.samples.length)" :accessible-label="title" /></div>
    <p class="hint">{{ w.precipitationHint }}</p>
    <DataDetails v-model:open="detailsOpen" :items="[]">
      <section v-for="(series, index) in resource.data.value" :key="index" class="day-detail">
        <h3>{{ addDays(start, index) }}</h3>
        <ElDescriptions :column="1" border><ElDescriptionsItem v-for="item in seriesDetails(series)" :key="item.label" :label="item.label">{{ item.value ?? w.missing }}</ElDescriptionsItem></ElDescriptions>
      </section>
    </DataDetails>
  </ElCard>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElOption, ElSelect } from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import DataDetails from './DataDetails.vue'
import { useResource } from '../composables/useResource'
import { weatherApi } from '../api/weather'
import type { WeatherSeries } from '../models/weather'
import { hourlyOption, metrics } from '../models/charts'
import { addDays, midnight } from '../models/presentation'
import { statusMessage } from '../models/status'
import { seriesDetails } from '../models/details'
import w from '../locales/zh-CN'
const props = defineProps<{ building: string; start: string; end: string; product: string; title: string; revision?: number }>()
const resource = useResource<WeatherSeries[]>()
const metric = ref('temperature_2m')
const detailsOpen = ref(false)
const from = computed(() => midnight(props.start))
const to = computed(() => midnight(addDays(props.end, 1)))
const option = computed(() => hourlyOption(resource.data.value ?? [], from.value, to.value, metric.value))
const statuses = computed(() => [...new Set((resource.data.value ?? []).map(s => statusMessage(s)))].join(' / '))
watch(() => [props.building, props.start, props.end, props.product, props.revision], () => {
  detailsOpen.value = false
  if (!props.building) { resource.clear(); return }
  void resource.load(JSON.stringify([props.building, props.start, props.end, props.product]), signal => weatherApi.hourly(props.building, props.product, from.value, to.value, signal))
}, { immediate: true })
</script>
<style scoped>
.heading { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
h2 { font-size: var(--bec-font-size-title); margin: 0; }
.heading :deep(.el-select) { width: var(--bec-navigation-width); }
.chart { height: var(--bec-chart-height); }
.hint { color: var(--bec-color-text-secondary); margin-bottom: 0; }
.day-detail { margin-bottom: var(--bec-space-section); overflow-wrap: anywhere; }
</style>
