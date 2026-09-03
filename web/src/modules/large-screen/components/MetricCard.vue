<script setup lang="ts">
import { ElCard } from '@/shared/ui'
import { formatNumber } from '@/shared/utils/format'
defineProps<{ label: string; value: number | null; unit?: string; format?: Intl.NumberFormatOptions }>()
</script>
<template>
  <ElCard shadow="never">
    <span class="metric-label">{{ label }}</span>
    <div class="metric-value">{{ formatNumber(value, format) }} <span class="metric-unit">{{ unit }}</span></div>
  </ElCard>
</template>
<style scoped>
.metric-label { font-size: var(--bec-monitor-font-panel); }
.metric-value { font-family: var(--bec-font-family-number); font-size: var(--bec-monitor-font-number); font-variant-numeric: tabular-nums; }
.metric-unit { font-family: var(--bec-font-family-body); font-size: var(--bec-monitor-font-body); }
</style>
