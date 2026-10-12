<template>
  <main class="weather-page">
    <WeatherToolbar v-model="buildings.selected.value" :title="w.historyTitle" :buildings="buildings.data.value ?? []" :loading="daily.loading.value || comparison.loading.value" @refresh="refresh" />
    <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    <ElEmpty v-if="!buildings.loading.value && !buildings.error.value && !buildings.data.value?.length" :description="w.noBuildings" />
    <template v-if="buildings.selected.value">
      <ElCard shadow="never">
        <ElTabs v-model="tab"><ElTabPane name="weather" :label="w.historyWeather" /><ElTabPane name="energy" :label="w.comparison" /></ElTabs>
        <div class="filters">
          <ElDatePicker v-model="range" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="w.start" :end-placeholder="w.end" :disabled-date="historyDateDisabled" :clearable="false" />
          <template v-if="tab === 'energy'">
            <ElSelect v-model="pointId" :aria-label="w.target" :placeholder="w.selectTarget" filterable :loading="targets.loading.value"><ElOption v-for="candidate in targets.data.value" :key="candidate.pointId" :value="candidate.pointId" :label="targetLabel(candidate)" /></ElSelect>
            <ElSelect v-model="metric" :aria-label="w.product"><ElOption value="temperature" :label="w.meanTemperature" /><ElOption value="humidity" :label="w.meanHumidity" /></ElSelect>
          </template>
          <ElButton type="primary" :disabled="!validRange" @click="query">{{ w.query }}</ElButton>
        </div>
        <ElAlert v-if="!validRange" :title="w.rangeInvalid" type="warning" :closable="false" />
        <template v-if="tab === 'weather'">
          <ElAlert v-if="daily.error.value" :title="daily.error.value" type="error" :closable="false" />
          <ElSkeleton v-if="daily.loading.value && !daily.data.value" :rows="5" animated />
          <ElTable v-else :data="daily.data.value ?? []" :empty-text="w.noData" max-height="520" stripe>
            <ElTableColumn :label="w.date" width="130"><template #default="{ row }"><ElButton link type="primary" @click="selectedDay = row.date">{{ row.date }}</ElButton></template></ElTableColumn>
            <ElTableColumn :label="w.weather" min-width="130"><template #default="{ row }">{{ row.sourceDaily?.text.weather || w.missing }}</template></ElTableColumn>
            <ElTableColumn :label="w.highLow" min-width="130"><template #default="{ row }">{{ valueText(row.sourceDaily?.values.temperature_2m_max, '°') }}{{ ' / ' }}{{ valueText(row.sourceDaily?.values.temperature_2m_min, '°') }}</template></ElTableColumn>
            <ElTableColumn :label="w.meanTemperature" min-width="110"><template #default="{ row }">{{ valueText(row.meanTemperature, '°C') }}</template></ElTableColumn>
            <ElTableColumn :label="w.meanHumidity" min-width="110"><template #default="{ row }">{{ valueText(row.meanHumidity, '%') }}</template></ElTableColumn>
            <ElTableColumn :label="w.status" min-width="120"><template #default="{ row }">{{ stateText(row.status) }}</template></ElTableColumn>
            <ElTableColumn width="110"><template #default="{ row }"><ElButton link type="primary" @click="openDaily(row as DailyWeather)">{{ w.details }}</ElButton></template></ElTableColumn>
          </ElTable>
        </template>
        <template v-else>
          <ElAlert v-if="targets.error.value || comparison.error.value" :title="targets.error.value || comparison.error.value" type="error" :closable="false" />
          <ElEmpty v-if="!targets.loading.value && !targets.error.value && !targets.data.value?.length" :description="w.noTargets" />
          <p v-if="target" class="hint">{{ target.meteringBoundaryName }}{{ ' · ' }}{{ w.targetHint }}</p>
          <div v-if="target" class="chart"><ChartView :option="chart" :loading="comparison.loading.value" :empty="!comparison.data.value?.days.length" :accessible-label="w.comparison" /></div>
          <ElTable v-if="target" :data="comparison.data.value?.days ?? []" :empty-text="w.noData" max-height="440" stripe>
            <ElTableColumn prop="date" :label="w.date" min-width="130" />
            <ElTableColumn :label="w.electricity" min-width="130"><template #default="{ row }">{{ valueText(row.energyKwh, ' kWh') }}</template></ElTableColumn>
            <ElTableColumn :label="metric === 'temperature' ? w.meanTemperature : w.meanHumidity" min-width="130"><template #default="{ row }">{{ valueText(row.weatherValue, row.weatherUnit) }}</template></ElTableColumn>
            <ElTableColumn :label="w.energyStatus" min-width="120"><template #default="{ row }">{{ stateText(row.energyStatus) }}</template></ElTableColumn>
            <ElTableColumn :label="w.weatherStatus" min-width="120"><template #default="{ row }">{{ stateText(row.weatherStatus) }}</template></ElTableColumn>
            <ElTableColumn width="110"><template #default="{ row }"><ElButton link type="primary" @click="openComparison(row as ComparisonDay)">{{ w.details }}</ElButton></template></ElTableColumn>
          </ElTable>
        </template>
      </ElCard>
      <HourlyChart v-if="tab === 'weather' && selectedDay" :building="buildings.selected.value" :start="selectedDay" :end="selectedDay" product="HISTORY_HOURLY" :title="w.hourlyHistory" :revision="revision">
        <template #date-controls>
          <div class="hourly-date">
            <span>{{ w.hourlyDate }}</span>
            <ElButton :disabled="selectedDay <= '2024-01-01'" @click="changeHourlyDay(addDays(selectedDay, -1))">{{ w.previousDay }}</ElButton>
            <ElDatePicker :model-value="selectedDay" type="date" value-format="YYYY-MM-DD" :aria-label="w.hourlyDate" :disabled-date="historyDateDisabled" :clearable="false" @update:model-value="changeHourlyDay" />
            <ElButton :disabled="selectedDay >= addDays(chinaDay(), -1)" @click="changeHourlyDay(addDays(selectedDay, 1))">{{ w.nextDay }}</ElButton>
            <span class="hint">{{ w.historyEndedHint }}</span>
          </div>
        </template>
      </HourlyChart>
    </template>
    <DataDetails v-model:open="detailsOpen" :items="details" />
  </main>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDatePicker, ElEmpty, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTabs, ElTabPane } from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import WeatherToolbar from '../components/WeatherToolbar.vue'
import HourlyChart from '../components/HourlyChart.vue'
import DataDetails from '../components/DataDetails.vue'
import { useBuildings } from '../composables/useBuildings'
import { useResource } from '../composables/useResource'
import { weatherApi } from '../api/weather'
import type { Comparison, ComparisonDay, DailyWeather, Target } from '../models/weather'
import { comparisonDetails, dailyDetails, type DetailItem } from '../models/details'
import { addDays, chinaDay, historyDateDisabled, historyRangeValid, stateText, valueText } from '../models/presentation'
import { comparisonOption } from '../models/charts'
import w from '../locales/zh-CN'
const buildings = useBuildings()
const daily = useResource<DailyWeather[]>()
const targets = useResource<Target[]>()
const comparison = useResource<Comparison>()
const tab = ref('weather')
const range = ref<string[]>([addDays(chinaDay(), -14), addDays(chinaDay(), -1)])
const validRange = computed(() => historyRangeValid(range.value))
const pointId = ref('')
const metric = ref('temperature')
const selectedDay = ref('')
const revision = ref(0)
const target = computed(() => targets.data.value?.find(t => t.pointId === pointId.value))
const chart = computed(() => comparisonOption(comparison.data.value?.days ?? [], metric.value))
const detailsOpen = ref(false)
const details = ref<DetailItem[]>([])
const targetLabel = (t: Target) => `${t.targetObjectName} / ${t.pointName || t.pointCode}`
function changeHourlyDay(day: unknown) {
  if (typeof day === 'string' && historyRangeValid([day, day])) selectedDay.value = day
}
function query() {
  const building = buildings.selected.value
  if (!building || !validRange.value) return
  const [start, end] = range.value
  if (tab.value === 'weather') {
    void daily.load(JSON.stringify([building, start, end]), signal => weatherApi.daily(building, start, end, signal))
    if (!selectedDay.value || selectedDay.value < start || selectedDay.value > end) selectedDay.value = end
  } else if (target.value) {
    const { systemId, pointId: point } = target.value; const selectedMetric = metric.value
    void comparison.load(JSON.stringify([building, start, end, point, selectedMetric]), signal => weatherApi.comparison(building, systemId, point, start, end, selectedMetric, signal))
  }
}
async function loadTargets() {
  const building = buildings.selected.value
  if (!building) return
  await targets.load(building, signal => weatherApi.targets(building, signal))
  if (building !== buildings.selected.value) return
  if (!targets.data.value?.some(t => t.pointId === pointId.value)) pointId.value = targets.data.value?.[0]?.pointId ?? ''
}
async function refresh() { revision.value++; if (!buildings.data.value?.length) await buildings.reload(); if (tab.value === 'energy') await loadTargets(); query() }
function openDaily(row: DailyWeather) { details.value = dailyDetails(row); detailsOpen.value = true }
function openComparison(row: ComparisonDay) { details.value = comparisonDetails(row); if (target.value) details.value.push({ label: w.pointCode, value: target.value.pointCode }, { label: w.boundary, value: target.value.meteringBoundaryName }); detailsOpen.value = true }
watch(buildings.selected, () => { daily.clear(); comparison.clear(); targets.clear(); pointId.value = ''; selectedDay.value = ''; detailsOpen.value = false; query(); if (tab.value === 'energy') void loadTargets() })
watch(tab, () => { detailsOpen.value = false; if (tab.value === 'energy') void loadTargets(); query() })
watch([range, metric, pointId], () => { daily.clear(); comparison.clear(); selectedDay.value = ''; detailsOpen.value = false; query() }, { deep: true })
</script>
<style scoped>
.weather-page { display: grid; gap: var(--bec-space-section); }
.filters { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); margin: var(--bec-space-group) 0; }
.filters :deep(.el-select) { width: var(--bec-navigation-width); }
.hourly-date { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.chart { height: var(--bec-chart-height); }
.hint { color: var(--bec-color-text-secondary); }
</style>
