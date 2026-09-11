<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElDatePicker, ElEmpty, ElOption, ElRadio, ElRadioGroup, ElSelect, ElSkeleton, ElTag, RefreshCw } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { useHvacDashboard } from '@/modules/dashboard/public'
import { useHvacHistoryTrends } from '../composables/useHvacHistoryTrends'
import type { HvacHistoryMode, HvacHistoryPreset } from '../models/hvac-history'
import TrendGroupChart from '../components/TrendGroupChart.vue'

const dashboard = useHvacDashboard()
const history = useHvacHistoryTrends()
const customRange = ref<[Date, Date] | null>(null)
const selectedBuilding = computed(() => dashboard.selectedBuildingId.value)

async function syncHistoryContext(): Promise<void> {
  const buildingId = dashboard.selectedBuildingId.value
  if (!buildingId) return
  await history.setBuildingContext({
    buildingId,
    indicatorIds: (dashboard.latestIndicators.value?.indicators ?? []).map(item => item.indicatorId).filter(Boolean),
    points: dashboard.snapshot.value?.points ?? [],
  })
}

async function initialize(): Promise<void> {
  await dashboard.initialize()
  await syncHistoryContext()
}

async function selectBuilding(value: string): Promise<void> {
  await dashboard.selectBuilding(value)
  await syncHistoryContext()
}

function setMode(value: string | number | boolean | undefined): void {
  void history.setMode(value as HvacHistoryMode)
}

function setPreset(value: string | number | boolean | undefined): void {
  void history.setPreset(value as HvacHistoryPreset)
}

function applyCustomRange(): void {
  const [from, to] = customRange.value ?? []
  void history.setCustomRange(from?.getTime() ?? null, to?.getTime() ?? null)
}

onMounted(() => { void initialize() })
onUnmounted(history.dispose)
</script>

<template>
  <section class="trend-page">
    <div class="page-heading">
      <div><h1>{{ t('trendAnalysis.title') }}</h1><p>{{ t('trendAnalysis.compatibilityNotice') }}</p></div>
      <div class="page-actions">
        <ElSelect class="building-select" :model-value="selectedBuilding" :placeholder="t('dashboard.building')" @change="selectBuilding">
          <ElOption v-for="building in dashboard.buildings.value" :key="building.buildingId" :label="building.buildingName" :value="building.buildingId" />
        </ElSelect>
        <ElButton :icon="RefreshCw" :loading="history.loading.value" @click="history.refresh">{{ t('trendAnalysis.refresh') }}</ElButton>
      </div>
    </div>

    <ElAlert v-if="dashboard.buildingError.value" :title="dashboard.buildingError.value" type="error" show-icon :closable="false" />
    <ElSkeleton v-if="dashboard.initializing.value" :rows="8" animated />
    <template v-else>
      <ElCard shadow="never">
        <div class="query-controls">
          <ElRadioGroup :model-value="history.mode.value" @change="setMode">
            <ElRadio value="indicators">{{ t('trendAnalysis.modeIndicator') }}</ElRadio>
            <ElRadio value="points">{{ t('trendAnalysis.modePoint') }}</ElRadio>
          </ElRadioGroup>
          <ElRadioGroup :model-value="history.preset.value" @change="setPreset">
            <ElRadio value="1h">{{ t('trendAnalysis.range1h') }}</ElRadio>
            <ElRadio value="6h">{{ t('trendAnalysis.range6h') }}</ElRadio>
            <ElRadio value="24h">{{ t('trendAnalysis.range24h') }}</ElRadio>
            <ElRadio value="7d">{{ t('trendAnalysis.range7d') }}</ElRadio>
            <ElRadio value="custom">{{ t('trendAnalysis.custom') }}</ElRadio>
          </ElRadioGroup>
          <ElSelect v-if="history.mode.value === 'points'" :model-value="history.selectedPointIds.value" multiple collapse-tags :placeholder="t('trendAnalysis.selectPoints')" @change="history.setSelectedPointIds">
            <ElOption v-for="point in history.pointOptions.value" :key="point.pointId" :label="point.label" :value="point.pointId" />
          </ElSelect>
          <div v-if="history.preset.value === 'custom'" class="custom-range">
            <ElDatePicker v-model="customRange" type="datetimerange" :start-placeholder="t('trendAnalysis.startTime')" :end-placeholder="t('trendAnalysis.endTime')" />
            <ElButton type="primary" @click="applyCustomRange">{{ t('trendAnalysis.query') }}</ElButton>
          </div>
        </div>
      </ElCard>

      <ElAlert v-if="history.error.value" :title="history.error.value.message" type="error" show-icon :closable="false" />
      <div v-if="history.updatedAt.value" class="trend-meta">
        <ElTag v-if="history.stale.value" type="warning">{{ t('common.stale') }}</ElTag>
        <span>{{ t('trendAnalysis.updatedAtValue', { time: formatDateTime(history.updatedAt.value) }) }}</span>
      </div>
      <ElEmpty v-if="!history.loading.value && history.groups.value.length === 0" :description="t('trendAnalysis.noSeries')" />
      <ElCard v-for="group in history.groups.value" :key="group.unit || 'unitless'" shadow="never">
        <h2>{{ group.unit || t('trendAnalysis.unitless') }}</h2>
        <TrendGroupChart :group="group" :loading="history.loading.value" />
      </ElCard>
    </template>
  </section>
</template>

<style scoped>
.trend-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .page-actions, .trend-meta, .custom-range { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
.query-controls { display: flex; align-items: center; flex-wrap: wrap; gap: var(--bec-space-group); }
h1, h2, p { margin: 0; }
h1 { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-system); }
h2 { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-title); }
p, .trend-meta { color: var(--bec-color-text-secondary); }
.building-select { width: var(--bec-navigation-width); }
</style>
