<script setup lang="ts">
import { computed } from 'vue'
import { t } from '@/locales'
import type { AssetPointReading } from '../../models/assets'
import { extractSinglePhaseMetrics } from './meter-display'
import MeterPointReadingTable from './MeterPointReadingTable.vue'
import MeterRealtimeTrendChart, { type MeterTrendRecord } from './MeterRealtimeTrendChart.vue'

const props = defineProps<{
  points: AssetPointReading[]
  trendRecords: MeterTrendRecord[]
  loading?: boolean
}>()

const metrics = computed(() => extractSinglePhaseMetrics(props.points))

const power = computed(() => metrics.value.power)
const energy = computed(() => metrics.value.energy)
const voltage = computed(() => metrics.value.voltage)
const current = computed(() => metrics.value.current)
const powerFactor = computed(() => metrics.value.powerFactor)
const frequency = computed(() => metrics.value.frequency)

function fmt(val: number | null, decimals = 2): string {
  if (val == null || Number.isNaN(val)) return '--'
  return Math.abs(val) >= 1000 ? val.toLocaleString('zh-CN', { maximumFractionDigits: decimals }) : val.toFixed(decimals)
}

function fmtMetric(val: number | null, unit: string): string {
  const decimals = unit === 'kW' ? 2 : 1
  return fmt(val, decimals) + ' ' + unit
}
</script>

<template>
  <div class="single-phase-board">
    <!-- 1. 单相大字核心指标卡片 -->
    <div class="kpi-grid">
      <div class="kpi-card power">
        <span class="kpi-label">{{ t('assetManagement.meter.singlePhasePower') }}</span>
        <div class="kpi-value font-mono">
          <span>{{ fmt(power, 2) }}</span>
          <span class="kpi-unit">{{ 'kW' }}</span>
        </div>
      </div>

      <div class="kpi-card energy">
        <span class="kpi-label">{{ t('assetManagement.meter.positiveEnergy') }}</span>
        <div class="kpi-value font-mono text-emerald">
          <span>{{ fmt(energy, 2) }}</span>
          <span class="kpi-unit">{{ 'kWh' }}</span>
        </div>
      </div>
    </div>

    <!-- 2. 单相电气参数 4 格网格 -->
    <div class="param-grid">
      <div class="param-card">
        <span class="param-label">{{ t('assetManagement.meter.voltage') }}</span>
        <div class="param-value font-mono"><span>{{ fmtMetric(voltage, 'V') }}</span></div>
      </div>

      <div class="param-card">
        <span class="param-label">{{ t('assetManagement.meter.current') }}</span>
        <div class="param-value font-mono"><span>{{ fmtMetric(current, 'A') }}</span></div>
      </div>

      <div class="param-card">
        <span class="param-label">{{ t('assetManagement.meter.powerFactor') }}</span>
        <div class="param-value font-mono text-blue"><span>{{ fmt(powerFactor, 2) }}</span></div>
      </div>

      <div class="param-card">
        <span class="param-label">{{ t('assetManagement.meter.frequency') }}</span>
        <div class="param-value font-mono"><span>{{ fmtMetric(frequency, 'Hz') }}</span></div>
      </div>
    </div>

    <!-- 3. 动态走势图 -->
    <MeterRealtimeTrendChart phase="1P" :records="props.trendRecords" :loading="props.loading" />

    <!-- 4. 读数清单表 -->
    <MeterPointReadingTable :points="props.points" />
  </div>
</template>

<style scoped>
.single-phase-board {
  display: grid;
  gap: var(--bec-space-section);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--bec-space-group);
}

.kpi-card {
  padding: var(--bec-space-group);
  border-radius: var(--bec-management-radius);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  background: var(--bec-color-surface-primary);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.kpi-label {
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.kpi-value {
  display: flex;
  align-items: baseline;
  gap: var(--bec-ref-space-4);
  font-size: var(--bec-font-size-title);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
  margin-top: var(--bec-ref-space-4);
}

.kpi-unit {
  font-size: var(--bec-font-size-small);
  font-weight: var(--bec-font-weight-normal);
  color: var(--bec-color-text-secondary);
}

.text-emerald { color: var(--bec-ref-green); }
.text-blue { color: var(--bec-ref-blue); }

.param-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--bec-space-group);
}

.param-card {
  padding: var(--bec-space-tight);
  background: var(--bec-color-surface-secondary);
  border-radius: var(--bec-management-radius);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  text-align: center;
}

.param-label {
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.param-value {
  margin-top: var(--bec-ref-space-4);
  font-size: var(--bec-font-size-body);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.param-unit {
  font-size: var(--bec-font-size-small);
  font-weight: var(--bec-font-weight-normal);
  color: var(--bec-color-text-secondary);
}
</style>
