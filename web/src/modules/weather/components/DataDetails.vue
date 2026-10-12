<template>
  <ElDrawer :model-value="open" :title="title || w.details" size="min(92%, 640px)" @update:model-value="emit('update:open', $event)">
    <ElDescriptions :column="1" border>
      <ElDescriptionsItem v-for="item in items" :key="item.label" :label="item.label"><span class="detail-value">{{ item.value ?? w.missing }}</span></ElDescriptionsItem>
    </ElDescriptions>
    <slot />
  </ElDrawer>
</template>
<script setup lang="ts">
import { ElDescriptions, ElDescriptionsItem, ElDrawer } from '@/shared/ui'
import w from '../locales/zh-CN'
export interface DetailItem { label: string; value: string | number | null | undefined }
defineProps<{ open: boolean; title?: string; items: DetailItem[] }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
</script>
<style scoped>
.detail-value { overflow-wrap: anywhere; }
</style>
