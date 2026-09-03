<script setup lang="ts">
import { computed } from 'vue'
import { ElTag, CircleAlert, CircleCheck, Clock } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
// 显式保留未提供状态，避免 Vue 将可选布尔属性转成 false 而误报“数据可用”。
const props = withDefaults(defineProps<{ updatedAt?: number | string | Date; stale?: boolean; timeZone?: string }>(), {
  updatedAt: undefined, stale: undefined, timeZone: undefined,
})
const updated = computed(() => props.updatedAt == null ? t('common.noUpdate')
  : t('common.updatedAt', { time: formatDateTime(props.updatedAt, props.timeZone) }))
</script>

<template>
  <div class="data-status" role="status">
    <Clock aria-hidden="true" /><span>{{ updated }}</span>
    <ElTag v-if="updatedAt != null && stale != null" :type="stale ? 'warning' : 'success'">
      <component :is="stale ? CircleAlert : CircleCheck" aria-hidden="true" />
      {{ t(stale ? 'common.stale' : 'common.ready') }}
    </ElTag>
  </div>
</template>

<style scoped>
.data-status { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; color: var(--bec-color-text-secondary); }
svg { width: var(--bec-icon-small); height: var(--bec-icon-small); vertical-align: middle; }
</style>
