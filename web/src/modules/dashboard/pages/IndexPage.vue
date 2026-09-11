<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElOption, ElProgress, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, RefreshCw } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { useHvacDashboard } from '../composables/useHvacDashboard'
import { useHvacCalculationDetail } from '../composables/useHvacCalculationDetail'
import type { DashboardIndicatorView } from '../mappers/hvac-dashboard'
import IndicatorCard from '../components/IndicatorCard.vue'
import CalculationDetailDrawer from '../components/CalculationDetailDrawer.vue'

const dashboard = useHvacDashboard()
const calculation = useHvacCalculationDetail()
const points = computed(() => Object.values(dashboard.pointViews.value))
const realtimeLabel = computed(() => ({
  connecting: t('dashboard.connecting'),
  realtime: t('dashboard.realtime'),
  reconnecting_with_http: t('dashboard.reconnecting'),
  http_fallback: t('dashboard.fallback'),
  forbidden: t('dashboard.forbidden'),
}[dashboard.realtimeState.value]))

async function initialize(): Promise<void> {
  await dashboard.initialize()
  dashboard.startRealtime()
}

async function selectBuilding(value: string): Promise<void> {
  calculation.close()
  await dashboard.selectBuilding(value)
}

function openCalculation(indicator: DashboardIndicatorView): void {
  void calculation.open({
    indicatorId: indicator.indicatorId,
    indicatorCode: indicator.indicatorCode,
    label: indicator.label,
    minuteStart: indicator.minuteStart,
  })
}

onMounted(() => { void initialize() })
onUnmounted(() => { calculation.close(); dashboard.stopRealtime() })
</script>

<template>
  <section class="dashboard-page">
    <div class="page-heading">
      <div>
        <h1>{{ t('dashboard.title') }}</h1>
        <p>{{ t('dashboard.compatibilityNotice') }}</p>
      </div>
      <div class="page-actions">
        <ElSelect class="building-select" :model-value="dashboard.selectedBuildingId.value" :placeholder="t('dashboard.building')" @change="selectBuilding">
          <ElOption v-for="building in dashboard.buildings.value" :key="building.buildingId" :label="building.buildingName" :value="building.buildingId" />
        </ElSelect>
        <ElButton :icon="RefreshCw" :loading="dashboard.refreshing.value" @click="dashboard.refresh">{{ t('dashboard.refresh') }}</ElButton>
      </div>
    </div>

    <ElAlert v-if="dashboard.buildingError.value" :title="dashboard.buildingError.value" type="error" show-icon :closable="false" />
    <ElSkeleton v-if="dashboard.initializing.value" :rows="8" animated />
    <ElEmpty v-else-if="dashboard.buildings.value.length === 0" :description="t('dashboard.noBuilding')" />
    <template v-else>
      <div class="status-line">
        <ElTag :type="dashboard.realtimeState.value === 'realtime' ? 'success' : 'warning'">{{ realtimeLabel }}</ElTag>
        <span>{{ t('dashboard.updatedAtValue', { time: dashboard.snapshotUpdatedAt.value ? formatDateTime(dashboard.snapshotUpdatedAt.value) : t('common.noUpdate') }) }}</span>
      </div>

      <section aria-labelledby="indicator-heading">
        <div class="section-heading"><h2 id="indicator-heading">{{ t('dashboard.indicators') }}</h2></div>
        <ElAlert v-if="dashboard.indicatorError.value" :title="dashboard.indicatorError.value" type="warning" show-icon :closable="false" />
        <div class="indicator-grid">
          <IndicatorCard v-for="indicator in dashboard.indicatorViews.value" :key="indicator.indicatorCode" :indicator="indicator" @detail="openCalculation" />
        </div>
      </section>

      <ElCard shadow="never">
        <div class="section-heading">
          <div><h2>{{ t('dashboard.points') }}</h2><p>{{ t('dashboard.coverage') }}</p></div>
          <ElProgress :percentage="dashboard.coveragePercent.value" />
        </div>
        <ElAlert v-if="dashboard.snapshotError.value" :title="dashboard.snapshotError.value" type="warning" show-icon :closable="false" />
        <ElTable :data="points" table-layout="fixed">
          <ElTableColumn prop="label" :label="t('dashboard.pointName')" />
          <ElTableColumn :label="t('dashboard.currentValue')"><template #default="{ row }">{{ row.displayValue }} {{ row.unit }}</template></ElTableColumn>
          <ElTableColumn :label="t('dashboard.lastValue')"><template #default="{ row }">{{ row.lastDisplayValue ?? t('common.missing') }}</template></ElTableColumn>
          <ElTableColumn prop="sampleCount" :label="t('dashboard.sampleCount')" />
          <ElTableColumn prop="qualityLabel" :label="t('dashboard.dataQuality')" />
          <ElTableColumn :label="t('dashboard.pointStatus')"><template #default="{ row }"><ElTag :type="row.status === 'NORMAL' ? 'success' : 'warning'">{{ row.statusLabel }}</ElTag></template></ElTableColumn>
        </ElTable>
      </ElCard>
    </template>

    <CalculationDetailDrawer :state="calculation" @close="calculation.close" />
  </section>
</template>

<style scoped>
.dashboard-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .section-heading, .page-actions, .status-line { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
.page-heading > div:first-child, .section-heading > div:first-child { min-width: 0; }
h1, h2, p { margin: 0; }
h1 { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-system); }
h2 { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-title); }
p, .status-line { color: var(--bec-color-text-secondary); }
.indicator-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--bec-space-group); margin-top: var(--bec-space-group); }
.section-heading :deep(.el-progress) { width: var(--bec-navigation-width); }
.building-select { width: var(--bec-navigation-width); }
</style>
