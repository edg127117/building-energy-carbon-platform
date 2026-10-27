<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElCard, ElPagination, ElTable, ElTableColumn } from '@/shared/ui'
import type { ReadingView } from '../models/classification'
import copy from '../locales/reading-diagnostics.zh-CN'

const props = withDefaults(defineProps<{ readings: ReadingView[]; pointNames?: Record<string, string> }>(), { pointNames: () => ({}) })
// 诊断由服务端返回；这里只分页和展示，不以零增量或连续次数推导设备状态。
const diagnostics = computed(() => props.readings.flatMap(row => row.unchangedReading ? [row.unchangedReading] : []))
const page = ref(1)
const pageSize = 10
const visible = computed(() => diagnostics.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(() => props.readings, () => { page.value = 1 })
const dateFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium' })

function dateLabel(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? copy.unavailable : dateFormat.format(date)
}
</script>

<template>
  <ElCard v-if="diagnostics.length" class="reading-diagnostics" shadow="never" data-test="reading-diagnostics">
    <template #header><h2>{{ copy.title }}</h2></template>
    <ElAlert :title="copy.description" type="info" :closable="false" show-icon />
    <p>{{ copy.rangeHint }}</p>
    <ElTable :data="visible">
      <ElTableColumn :label="copy.point" min-width="160"><template #default="{ row }">{{ pointNames[row.pointId] || copy.unnamedPoint }}</template></ElTableColumn>
      <ElTableColumn :label="copy.start" min-width="150"><template #default="{ row }">{{ dateLabel(row.startInclusive) }}</template></ElTableColumn>
      <ElTableColumn :label="copy.end" min-width="170"><template #default="{ row }">{{ dateLabel(row.endExclusive) }}</template></ElTableColumn>
      <ElTableColumn :label="copy.count" min-width="130"><template #default="{ row }">{{ row.intervalCount }}</template></ElTableColumn>
      <ElTableColumn :label="copy.counter" min-width="170"><template #default="{ row }">{{ row.counterValue }} {{ row.unit }}</template></ElTableColumn>
      <ElTableColumn :label="copy.details" min-width="230">
        <template #default="{ row }">
          <details>
            <summary>{{ copy.details }}</summary>
            <dl><dt>{{ copy.pointId }}</dt><dd>{{ row.pointId }}</dd><dt>{{ copy.energy }}</dt><dd>{{ row.energyItemCode }}</dd></dl>
            <dl v-for="source in row.sources" :key="source.factId">
              <dt>{{ copy.fact }}</dt><dd>{{ source.factId }}</dd>
              <dt>{{ copy.batch }}</dt><dd>{{ source.batchId }}</dd>
              <dt>{{ copy.evidence }}</dt><dd>{{ source.sourceReference }}</dd>
            </dl>
          </details>
        </template>
      </ElTableColumn>
    </ElTable>
    <ElPagination v-if="diagnostics.length > pageSize" v-model:current-page="page" :page-size="pageSize" :total="diagnostics.length" layout="prev, pager, next, total" />
  </ElCard>
</template>

<style scoped>
.reading-diagnostics { min-width: 0; }
h2, p { margin: 0; }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
p { margin-block: var(--bec-space-group); color: var(--bec-color-text-secondary); }
summary { cursor: pointer; color: var(--bec-color-text-secondary); }
dl { margin-block: var(--bec-space-group); }
dt { color: var(--bec-color-text-secondary); }
dd { margin-inline: 0; overflow-wrap: anywhere; }
.reading-diagnostics :deep(.el-pagination) { margin-top: var(--bec-space-group); flex-wrap: wrap; }
</style>
