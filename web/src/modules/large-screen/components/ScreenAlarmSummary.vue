<script setup lang="ts">
import { computed } from 'vue'
import { Bell, CircleAlert } from '@/shared/ui'
import { t } from '@/locales'
import type { ScreenAlarmSummaryData } from '../models/screen-summaries'

const props = defineProps<{ data?: ScreenAlarmSummaryData }>()
const numberFormatter = new Intl.NumberFormat('zh-CN')

function displayNumber(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? numberFormatter.format(value)
    : t('largeScreen.alarmSummaryMissing')
}

const summaries = computed(() => [
  {
    key: 'device',
    icon: CircleAlert,
    label: t('largeScreen.alarmSummaryDeviceAnomalies'),
    count: props.data?.deviceAnomalies,
    unit: t('largeScreen.alarmSummaryDeviceUnit'),
    tone: 'device-anomaly',
  },
  {
    key: 'emission',
    icon: Bell,
    label: t('largeScreen.alarmSummaryEmissionWarnings'),
    count: props.data?.emissionWarnings,
    unit: t('largeScreen.alarmSummaryWarningUnit'),
    tone: 'emission-warning',
  },
])
</script>

<template>
  <section class="alarm-summary" :aria-label="t('largeScreen.alarmSummaryLabel')">
    <article v-for="summary in summaries" :key="summary.key" class="alarm-summary-card" :class="summary.tone" :data-testid="'alarm-' + summary.key" :aria-label="t('largeScreen.alarmSummaryCount', { label: summary.label, count: displayNumber(summary.count), unit: summary.unit })">
      <component :is="summary.icon" :size="24" aria-hidden="true" />
      <div><span>{{ summary.label }}</span><strong>{{ displayNumber(summary.count) }}<small>{{ summary.unit }}</small></strong></div>
    </article>
  </section>
</template>

<style scoped>
.alarm-summary { width: 100%; flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: repeat(2, minmax(0, 1fr)); gap: var(--bec-screen-space-8); }
.alarm-summary-card { min-width: 0; display: flex; align-items: center; gap: var(--bec-screen-space-8); padding: var(--bec-screen-space-12); color: var(--bec-screen-summary-tone); background: var(--bec-screen-detail-panel); border: var(--bec-screen-border-width) solid var(--bec-screen-summary-tone); border-radius: var(--bec-screen-radius); }
.alarm-summary-card>div { min-width: 0; flex: 1; display: flex; align-items: center; justify-content: space-between; gap: var(--bec-screen-space-8); }
.alarm-summary-card span { color: var(--bec-screen-ink); font-size: var(--bec-screen-font-body); font-weight: var(--bec-screen-weight); }
.alarm-summary-card strong { color: var(--bec-screen-summary-tone); font-size: var(--bec-screen-font-large); font-weight: var(--bec-screen-weight); line-height: var(--bec-screen-line-tight); white-space: nowrap; }
.alarm-summary-card small { margin-left: var(--bec-screen-space-6); color: var(--bec-screen-muted); font-size: var(--bec-screen-font-small); font-weight: var(--bec-screen-weight); }
.device-anomaly { --bec-screen-summary-tone: var(--bec-screen-alarm-red); }
.emission-warning { --bec-screen-summary-tone: var(--bec-screen-orange); }
</style>
