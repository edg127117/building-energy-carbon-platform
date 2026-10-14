<template>
  <main class="weather-page">
    <WeatherToolbar v-model="buildings.selected.value" :title="w.ingestionTitle" :buildings="buildings.data.value ?? []" :disabled="busy" :loading="management.loading.value || jobs.loading.value" @refresh="refresh" />
    <ElAlert v-if="buildings.error.value || management.error.value" :title="buildings.error.value || management.error.value" type="error" :closable="false" />
    <ElEmpty v-if="!buildings.loading.value && !buildings.error.value && !buildings.data.value?.length" :description="w.noBuildings" />
    <ElSkeleton v-if="management.loading.value && !management.data.value" :rows="4" animated />
    <template v-if="management.data.value && buildings.selected.value">
      <ElCard v-if="management.data.value.canManageLocation" shadow="never">
        <template #header><div class="heading"><h2>{{ w.latestLocation }}</h2><ElButton :disabled="busy" @click="editorOpen = true">{{ binding ? w.edit : w.create }}</ElButton></div></template>
        <ElDescriptions v-if="binding" :column="2"><ElDescriptionsItem :label="w.name">{{ binding.name }}</ElDescriptionsItem><ElDescriptionsItem :label="w.coordinates">{{ binding.latitude }}{{ ', ' }}{{ binding.longitude }}</ElDescriptionsItem><ElDescriptionsItem :label="w.coverage">{{ w.coverages[binding.coverage] || w.missing }}</ElDescriptionsItem><ElDescriptionsItem :label="w.effectiveDate">{{ timeText(binding.effectiveFrom) }}</ElDescriptionsItem><ElDescriptionsItem :label="w.status">{{ binding.enabled ? w.enabled : w.disabled }}</ElDescriptionsItem></ElDescriptions>
        <p v-else>{{ w.noLocation }}</p>
        <p class="hint">{{ w.pendingFuture }}</p>
      </ElCard>
      <div class="task-forms">
        <TaskForm v-if="management.data.value.canFetchWeather" :key="`weather:${buildings.selected.value}`" :building="buildings.selected.value" kind="weather" @pending="fetchPending = $event" @submitted="reloadJobs" />
        <TaskForm v-if="management.data.value.canCalculateEnergy" :key="`energy:${buildings.selected.value}`" :building="buildings.selected.value" kind="energy" @pending="energyPending = $event" @submitted="reloadJobs" />
      </div>
      <ElAlert v-if="!management.data.value.canFetchWeather && !management.data.value.canCalculateEnergy" :title="w.noActions" type="info" :closable="false" />
      <ElCard shadow="never">
        <template #header><div class="heading"><h2>{{ w.recentJobs }}</h2><ElButton :loading="jobs.loading.value" @click="loadJobs">{{ w.refresh }}</ElButton></div></template>
        <ElAlert v-if="jobs.error.value" :title="jobs.error.value" type="error" :closable="false" />
        <ElTable :data="jobs.data.value?.items ?? []" :empty-text="w.noData" stripe>
          <ElTableColumn :label="w.taskContent" min-width="160"><template #default="{ row }">{{ row.kind === 'ENERGY' ? w.energyTask : w.products[row.product] || w.weatherTask }}</template></ElTableColumn>
          <ElTableColumn :label="w.range" min-width="220"><template #default="{ row }">{{ row.start }}{{ ' — ' }}{{ row.end }}</template></ElTableColumn>
          <ElTableColumn :label="w.status" min-width="110"><template #default="{ row }">{{ stateText(row.state) }}</template></ElTableColumn>
          <ElTableColumn :label="w.createdAt" min-width="180"><template #default="{ row }">{{ timeText(row.createdAt) }}</template></ElTableColumn>
          <ElTableColumn width="100"><template #default="{ row }"><ElButton link type="primary" @click="showJob(row as Job)">{{ w.jobDetails }}</ElButton></template></ElTableColumn>
        </ElTable>
        <ElPagination v-model:current-page="page" :page-size="10" :total="jobs.data.value?.total ?? 0" layout="prev, pager, next, total" />
      </ElCard>
      <LocationEditor v-model:open="editorOpen" :building="buildings.selected.value" :binding="binding" @pending="locationPending = $event" @saved="loadManagement" />
    </template>
    <DataDetails v-model:open="jobOpen" :title="w.jobDetails" :items="jobResource.data.value ? jobDetails(jobResource.data.value) : []">
      <ElAlert v-if="jobResource.error.value" :title="jobResource.error.value" type="error" :closable="false" />
      <ElButton :loading="jobResource.loading.value" @click="refreshJob">{{ w.refreshJob }}</ElButton>
    </DataDetails>
  </main>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElEmpty, ElPagination, ElSkeleton, ElTable, ElTableColumn } from '@/shared/ui'
import WeatherToolbar from '../components/WeatherToolbar.vue'
import LocationEditor from '../components/LocationEditor.vue'
import TaskForm from '../components/TaskForm.vue'
import DataDetails from '../components/DataDetails.vue'
import { useBuildings } from '../composables/useBuildings'
import { useResource } from '../composables/useResource'
import { weatherApi } from '../api/weather'
import type { Job, Management, Page } from '../models/weather'
import { jobDetails } from '../models/details'
import { stateText, timeText } from '../models/presentation'
import w from '../locales/zh-CN'
const buildings = useBuildings()
const management = useResource<Management>()
const jobs = useResource<Page<Job>>()
const jobResource = useResource<Job>()
const page = ref(1)
const editorOpen = ref(false)
const jobOpen = ref(false)
const jobId = ref('')
const fetchPending = ref(false)
const energyPending = ref(false)
const locationPending = ref(false)
const busy = computed(() => fetchPending.value || energyPending.value || locationPending.value)
const binding = computed(() => management.data.value?.latestBinding ?? null)
function loadManagement() { const building = buildings.selected.value; if (building) void management.load(building, signal => weatherApi.management(building, signal)) }
function loadJobs() { const building = buildings.selected.value; const currentPage = page.value; if (building) void jobs.load(`${building}:${currentPage}`, signal => weatherApi.jobs(building, currentPage, signal)) }
function reloadJobs() { if (page.value === 1) loadJobs(); else page.value = 1 }
async function refresh() { if (!buildings.data.value?.length) await buildings.reload(); loadManagement(); loadJobs() }
function showJob(job: Job) { jobId.value = job.id; jobResource.clear(); jobResource.data.value = job; jobOpen.value = true }
function refreshJob() { const id = jobId.value; if (id) void jobResource.load(id, signal => weatherApi.job(id, signal)) }
watch(buildings.selected, () => { management.clear(); jobs.clear(); jobResource.clear(); editorOpen.value = false; jobOpen.value = false; page.value = 1; loadManagement(); loadJobs() })
watch(page, loadJobs)
</script>
<style scoped>
.weather-page { display: grid; gap: var(--bec-space-section); }
.heading { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-group); }
h2 { font-size: var(--bec-font-size-title); margin: 0; }
.task-forms { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--bec-chart-height)), 1fr)); gap: var(--bec-space-group); }
.hint { color: var(--bec-color-text-secondary); }
.el-pagination { margin-top: var(--bec-space-group); justify-content: flex-end; }
</style>
