<script setup lang="ts">
import { ElEmpty, ElTable, ElTableColumn, ElTag, ElTooltip } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetPointReading } from '../../models/assets'
import { getPointDisplayName, getQualityInfo } from './meter-display'

const props = defineProps<{
  points: AssetPointReading[]
}>()

function formatReadingValue(val: number | null | undefined): string {
  if (val == null || Number.isNaN(val)) return '--'
  return Math.abs(val) >= 1000 ? val.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : val.toFixed(2)
}

function asReading(row: unknown): AssetPointReading {
  return row as AssetPointReading
}
</script>

<template>
  <div class="meter-points-table-container">
    <div class="table-heading">
      <h4 class="section-title">{{ t('assetManagement.meter.pointsListTitle') }}</h4>
      <div class="quality-legend">
        <span class="legend-item"><span class="dot q0" /><span>{{ t('assetManagement.meter.q0Legend') }}</span></span>
        <span class="legend-item"><span class="dot q1" /><span>{{ t('assetManagement.meter.q1Legend') }}</span></span>
        <span class="legend-item"><span class="dot q2" /><span>{{ t('assetManagement.meter.q2Legend') }}</span></span>
      </div>
    </div>

    <ElTable :data="props.points" row-key="pointId" stripe border class="reading-table">
      <ElTableColumn :label="t('assetManagement.meter.pointNameHeader')" min-width="180">
        <template #default="{ row }">
          <div class="point-name-cell">
            <span class="name-text">{{ getPointDisplayName(asReading(row)) }}</span>
            <span class="code-sub">{{ asReading(row).pointCode }}</span>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn :label="t('assetManagement.meter.realtimeValueHeader')" min-width="130" align="right">
        <template #default="{ row }">
          <span class="value-text font-mono">{{ formatReadingValue(asReading(row).value) }}</span>
        </template>
      </ElTableColumn>

      <ElTableColumn :label="t('assetManagement.meter.unitHeader')" prop="unit" min-width="70" align="center">
        <template #default="{ row }">
          <span class="unit-text">{{ asReading(row).unit || '--' }}</span>
        </template>
      </ElTableColumn>

      <ElTableColumn :label="t('assetManagement.meter.qualityHeader')" min-width="110" align="center">
        <template #default="{ row }">
          <ElTooltip :content="getQualityInfo(asReading(row).dataQuality).description" placement="top">
            <ElTag :type="getQualityInfo(asReading(row).dataQuality).tone" size="small" class="quality-tag">
              {{ getQualityInfo(asReading(row).dataQuality).label }}
            </ElTag>
          </ElTooltip>
        </template>
      </ElTableColumn>

      <template #empty>
        <ElEmpty :description="t('assetManagement.meter.emptyReadings')" />
      </template>
    </ElTable>
  </div>
</template>

<style scoped>
.meter-points-table-container {
  display: grid;
  gap: var(--bec-space-tight);
}

.table-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.section-title {
  margin: 0;
  font-size: var(--bec-font-size-body);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.quality-legend {
  display: flex;
  align-items: center;
  gap: var(--bec-space-group);
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
}

.dot {
  width: var(--bec-ref-space-8);
  height: var(--bec-ref-space-8);
  border-radius: var(--bec-ref-radius-pill);
}
.dot.q0 { background: var(--bec-ref-green); }
.dot.q1 { background: var(--bec-ref-blue); }
.dot.q2 { background: var(--bec-ref-disabled); }

.reading-table {
  width: 100%;
  border-radius: var(--bec-management-radius);
  overflow: hidden;
}

.point-name-cell {
  display: flex;
  flex-direction: column;
  gap: var(--bec-ref-space-4);
}

.name-text {
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.code-sub {
  font-size: var(--bec-font-size-small);
  color: var(--bec-ref-disabled);
  font-family: var(--bec-font-family-number);
}

.value-text {
  font-weight: var(--bec-font-weight-heading);
  font-size: var(--bec-font-size-body);
  color: var(--bec-color-text-primary);
}

.unit-text {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-font-size-small);
}

.quality-tag {
  cursor: help;
  font-weight: var(--bec-font-weight-normal);
}
</style>
