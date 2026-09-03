<script setup lang="ts">
import { ElCard, ElTag } from '@/shared/ui'
import DataStatus from '@/shared/components/DataStatus.vue'
import { t } from '@/locales'

// 状态文字与新鲜度由调用方按契约传入；时间戳本身不能证明设备或数据正常。
withDefaults(defineProps<{
  title: string
  statusText?: string
  tone?: 'success' | 'warning' | 'danger' | 'info'
  updatedAt?: number | string | Date
  stale?: boolean
  timeZone?: string
}>(), { statusText: undefined, tone: undefined, updatedAt: undefined, stale: undefined, timeZone: undefined })
</script>
<template>
  <ElCard shadow="never">
    <div class="status-card">
      <h2>{{ title }}</h2>
      <ElTag :type="tone ?? 'info'">{{ statusText ?? t('common.missing') }}</ElTag>
      <DataStatus :updated-at="updatedAt" :stale="stale" :time-zone="timeZone" />
    </div>
  </ElCard>
</template>
<style scoped>
.status-card { display: grid; justify-items: start; gap: var(--bec-monitor-gap); }
h2 { margin: 0; font-size: var(--bec-monitor-font-panel); }
</style>
