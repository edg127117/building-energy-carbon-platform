<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetPointReading } from '../../models/assets'
import MeterPointReadingTable from './MeterPointReadingTable.vue'
import MeterRealtimeTrendChart, { type MeterTrendRecord } from './MeterRealtimeTrendChart.vue'
import { calculateCurrentUnbalance, extractThreePhaseMetrics } from './meter-display'

const props = defineProps<{
  points: AssetPointReading[]
  trendRecords: MeterTrendRecord[]
  loading?: boolean
}>()

const metrics = computed(() => extractThreePhaseMetrics(props.points))

const pTotal = computed(() => metrics.value.pTotal)
const energy = computed(() => metrics.value.energy)
const pfTotal = computed(() => metrics.value.pfTotal)

const uA = computed(() => metrics.value.uA)
const uB = computed(() => metrics.value.uB)
const uC = computed(() => metrics.value.uC)

const iA = computed(() => metrics.value.iA)
const iB = computed(() => metrics.value.iB)
const iC = computed(() => metrics.value.iC)

const pA = computed(() => metrics.value.pA)
const pB = computed(() => metrics.value.pB)
const pC = computed(() => metrics.value.pC)

const pfA = computed(() => metrics.value.pfA)
const pfB = computed(() => metrics.value.pfB)
const pfC = computed(() => metrics.value.pfC)

const balanceInfo = computed(() => calculateCurrentUnbalance(iA.value, iB.value, iC.value))

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
  <div class="three-phase-board">
    <!-- 1. 顶部三大核心指标 (通俗中文大字) -->
    <div class="kpi-grid">
      <div class="kpi-card power">
        <span class="kpi-label">{{ t('assetManagement.meter.realtimePower') }}</span>
        <div class="kpi-value font-mono">
          <span>{{ fmt(pTotal, 2) }}</span>
          <span class="kpi-unit">{{ 'kW' }}</span>
        </div>
      </div>

      <div class="kpi-card energy">
        <span class="kpi-label">{{ t('assetManagement.meter.positiveEnergy') }}</span>
        <div class="kpi-value font-mono text-emerald">
          <span>{{ fmt(energy, 1) }}</span>
          <span class="kpi-unit">{{ 'kWh' }}</span>
        </div>
      </div>

      <div class="kpi-card pf">
        <div class="kpi-header">
          <span class="kpi-label">{{ t('assetManagement.meter.powerFactor') }}</span>
          <ElTag v-if="pfTotal != null" :type="pfTotal >= 0.9 ? 'success' : 'warning'" size="small">
            {{ pfTotal >= 0.9 ? t('assetManagement.meter.pfGood') : t('assetManagement.meter.pfLow') }}
          </ElTag>
        </div>
        <div class="kpi-value font-mono text-blue">
          <span>{{ fmt(pfTotal, 2) }}</span>
        </div>
      </div>
    </div>

    <!-- 2. A/B/C 三相负荷平衡对比卡 -->
    <div class="phase-balance-card">
      <div class="phase-header">
        <span class="phase-title">{{ t('assetManagement.meter.threePhaseBalanceTitle') }}</span>
        <ElTag :type="balanceInfo.tone" size="small" class="balance-tag">
          {{ balanceInfo.label }}
        </ElTag>
      </div>

      <div class="phase-columns">
        <!-- A相 -->
        <div class="phase-col a">
          <div class="phase-col-title phase-a">
            <span class="phase-dot a" />
            <span>{{ t('assetManagement.meter.phaseACircuit') }}</span>
          </div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseVoltage') }}</span><b>{{ fmtMetric(uA, 'V') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseCurrent') }}</span><b>{{ fmtMetric(iA, 'A') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePower') }}</span><b>{{ fmtMetric(pA, 'kW') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePowerFactor') }}</span><b>{{ fmt(pfA, 2) }}</b></div>
        </div>

        <!-- B相 -->
        <div class="phase-col b">
          <div class="phase-col-title phase-b">
            <span class="phase-dot b" />
            <span>{{ t('assetManagement.meter.phaseBCircuit') }}</span>
          </div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseVoltage') }}</span><b>{{ fmtMetric(uB, 'V') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseCurrent') }}</span><b>{{ fmtMetric(iB, 'A') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePower') }}</span><b>{{ fmtMetric(pB, 'kW') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePowerFactor') }}</span><b>{{ fmt(pfB, 2) }}</b></div>
        </div>

        <!-- C相 -->
        <div class="phase-col c">
          <div class="phase-col-title phase-c">
            <span class="phase-dot c" />
            <span>{{ t('assetManagement.meter.phaseCCircuit') }}</span>
          </div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseVoltage') }}</span><b>{{ fmtMetric(uC, 'V') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phaseCurrent') }}</span><b>{{ fmtMetric(iC, 'A') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePower') }}</span><b>{{ fmtMetric(pC, 'kW') }}</b></div>
          <div class="phase-metric"><span>{{ t('assetManagement.meter.phasePowerFactor') }}</span><b>{{ fmt(pfC, 2) }}</b></div>
        </div>
      </div>
    </div>

    <!-- 3. 动态走势图 -->
    <MeterRealtimeTrendChart phase="3P" :records="props.trendRecords" :loading="props.loading" />

    <!-- 4. 全量测点清单 -->
    <MeterPointReadingTable :points="props.points" />
  </div>
</template>

<style scoped>
.three-phase-board {
  display: grid;
  gap: var(--bec-space-section);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.kpi-header {
  display: flex;
  align-items: center;
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

.phase-balance-card {
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-management-radius);
  background: var(--bec-color-surface-primary);
  overflow: hidden;
}

.phase-header {
  padding: var(--bec-ref-space-8) var(--bec-space-section);
  background: var(--bec-color-surface-secondary);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.phase-title {
  font-weight: var(--bec-font-weight-heading);
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-primary);
}

.phase-columns {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--bec-space-group);
  padding: var(--bec-space-group);
}

.phase-col {
  padding: var(--bec-space-tight);
  background: var(--bec-color-surface-secondary);
  border-radius: var(--bec-management-radius);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  font-size: var(--bec-font-size-small);
}

.phase-col-title {
  font-weight: var(--bec-font-weight-heading);
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
  margin-bottom: var(--bec-ref-space-8);
}

.phase-dot {
  width: var(--bec-ref-space-8);
  height: var(--bec-ref-space-8);
  border-radius: var(--bec-ref-radius-pill);
}
.phase-dot.a { background: var(--bec-ref-amber); }
.phase-dot.b { background: var(--bec-ref-green); }
.phase-dot.c { background: var(--bec-ref-red); }

.phase-a { color: var(--bec-ref-amber); }
.phase-b { color: var(--bec-ref-green); }
.phase-c { color: var(--bec-ref-red); }

.phase-metric {
  display: flex;
  justify-content: space-between;
  margin-top: var(--bec-ref-space-4);
  color: var(--bec-color-text-secondary);
}

.phase-metric b {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
}
</style>
