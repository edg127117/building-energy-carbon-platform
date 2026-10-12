<template>
  <main class="weather-page">
    <WeatherToolbar v-model="buildings.selected.value" :title="w.overviewTitle" :buildings="buildings.data.value ?? []" :loading="overview.loading.value || buildings.loading.value" @refresh="refresh" />
    <ElAlert v-if="buildings.error.value || overview.error.value" :title="buildings.error.value || overview.error.value" type="error" :closable="false" />
    <ElEmpty v-if="!buildings.loading.value && !buildings.error.value && !buildings.data.value?.length" :description="w.noBuildings" />
    <ElSkeleton v-if="overview.loading.value && !overview.data.value" :rows="6" animated />
    <ElAlert v-else-if="overview.data.value && !overview.data.value.location?.enabled" :title="w.notConfigured" type="info" :closable="false" />
    <template v-if="overview.data.value?.location?.enabled">
      <div class="summary">
        <ElCard shadow="never" class="current">
          <template #header><div class="heading"><h2>{{ w.current }}</h2><ElButton text @click="showDetails('current')">{{ w.details }}</ElButton></div></template>
          <p class="hint">{{ overview.data.value.location.name }}{{ ' · ' }}{{ w.model }}</p>
          <div class="temperature">{{ valueText(sample?.values.temperature_2m, '°C') }}</div>
          <p class="condition">{{ sample?.text.weather || w.noData }}</p>
          <dl class="metrics"><div><dt>{{ w.humidity }}</dt><dd>{{ valueText(sample?.values.relative_humidity_2m, '%') }}</dd></div><div><dt>{{ w.wind }}</dt><dd>{{ valueText(sample?.values.wind_speed_10m, ' m/s') }}</dd></div><div><dt>{{ w.direction }}</dt><dd>{{ valueText(sample?.values.wind_direction_10m, '°') }}</dd></div></dl>
          <p class="hint">{{ w.observedAt }}{{ '：' }}{{ timeText(sample?.time) }}</p>
          <p v-if="overview.data.value.current.status !== 'READY' || overview.data.value.current.updateStatus !== 'FRESH'" class="status">{{ statusMessage(overview.data.value.current) }}</p>
        </ElCard>
        <ElCard shadow="never" class="forecast">
          <template #header><div class="heading"><h2>{{ w.forecast }}</h2><ElButton text @click="showDetails('forecast')">{{ w.details }}</ElButton></div></template>
          <div class="heading hint"><span>{{ sourceText(overview.data.value.forecast.selectedSource) }}</span><span>{{ w.fetchedAt }}{{ '：' }}{{ timeText(overview.data.value.forecast.fetchedAt) }}</span></div>
          <p v-if="fallback" class="status">{{ w.fallback }}</p>
          <p v-if="overview.data.value.forecast.status !== 'READY' || overview.data.value.forecast.updateStatus !== 'FRESH'" class="status">{{ forecastMessage(overview.data.value.forecast) }}</p>
          <div class="forecast-days">
            <article v-for="day in overview.data.value.forecast.days" :key="day.date">
              <time>{{ day.date.slice(5) }}</time>
              <WeatherIcon :weather="day.sample?.text.weather" />
              <p>{{ day.sample?.text.weather || w.missing }}</p>
              <strong>{{ valueText(day.sample?.values.temperature_2m_max, '°') }}</strong>
              <span class="hint">{{ valueText(day.sample?.values.temperature_2m_min, '°') }}</span>
              <small class="hint">{{ day.sample?.text.wind || valueText(day.sample?.values.wind_speed_10m_max, ' m/s') }}</small>
            </article>
          </div>
        </ElCard>
      </div>
      <HourlyChart :building="buildings.selected.value" :start="today" :end="addDays(today, 6)" product="FORECAST_HOURLY" :title="w.hourly" :revision="revision" />
    </template>
    <DataDetails v-model:open="detailsOpen" :items="details" />
  </main>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElSkeleton } from '@/shared/ui'
import WeatherIcon from '../components/WeatherIcon.vue'
import WeatherToolbar from '../components/WeatherToolbar.vue'
import HourlyChart from '../components/HourlyChart.vue'
import DataDetails from '../components/DataDetails.vue'
import { useBuildings } from '../composables/useBuildings'
import { useResource } from '../composables/useResource'
import { weatherApi } from '../api/weather'
import type { Overview } from '../models/weather'
import { seriesDetails, type DetailItem } from '../models/details'
import { addDays, chinaDay, sourceText, timeText, valueText } from '../models/presentation'
import { statusMessage, forecastMessage } from '../models/status'
import w from '../locales/zh-CN'
const buildings = useBuildings()
const overview = useResource<Overview>()
const today = ref(chinaDay())
const revision = ref(0)
const detailsOpen = ref(false)
const details = ref<DetailItem[]>([])
const sample = computed(() => overview.data.value?.current.samples.at(-1))
const fallback = computed(() => overview.data.value?.forecast.selectedSource === 'OPEN_METEO' && overview.data.value.forecast.status === 'READY')
function load() { const building = buildings.selected.value; if (building) void overview.load(building, signal => weatherApi.overview(building, signal)); else overview.clear() }
async function refresh() { today.value = chinaDay(); revision.value++; if (!buildings.data.value?.length) await buildings.reload(); load() }
function showDetails(section: 'current' | 'forecast') { const data = overview.data.value; if (!data) return; details.value = seriesDetails(data[section]); detailsOpen.value = true }
watch(buildings.selected, () => { detailsOpen.value = false; load() })
</script>
<style scoped>
.weather-page { display: grid; gap: var(--bec-space-section); }
.summary { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2.5fr); gap: var(--bec-space-group); }
.heading { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
h2 { margin: 0; font-size: var(--bec-font-size-title); }
.hint { color: var(--bec-color-text-secondary); }
.temperature { font-size: var(--bec-ref-font-44); font-family: var(--bec-font-family-number); }
.condition { margin-top: 0; }
.metrics { display: flex; justify-content: space-between; gap: var(--bec-space-tight); }
dt { color: var(--bec-color-text-secondary); } dd { margin: var(--bec-space-tight) 0 0; }
.status { color: var(--bec-color-warning); }
.forecast-days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: var(--bec-space-tight); margin-top: var(--bec-space-section); }
.forecast-days article { display: flex; align-items: center; flex-direction: column; gap: var(--bec-space-tight); text-align: center; }
.forecast-days p { margin: 0; min-height: var(--bec-ref-space-48); }
@media (max-width: 1200px) { .summary { grid-template-columns: 1fr; } .metrics { justify-content: flex-start; gap: var(--bec-space-section); } }
@media (max-width: 700px) { .forecast-days { grid-template-columns: repeat(4, minmax(0, 1fr)); row-gap: var(--bec-space-section); } }
</style>
