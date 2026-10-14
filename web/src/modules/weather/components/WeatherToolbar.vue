<template>
  <header class="toolbar">
    <h1>{{ title }}</h1>
    <div class="controls">
      <ElSelect :model-value="modelValue" :aria-label="w.building" :placeholder="w.selectBuilding" filterable :disabled="disabled" @update:model-value="emit('update:modelValue', $event)">
        <ElOption v-for="building in buildings" :key="building.buildingId" :value="building.buildingId" :label="building.buildingName" />
      </ElSelect>
      <ElButton :loading="loading" @click="emit('refresh')">{{ w.refresh }}</ElButton>
    </div>
  </header>
</template>
<script setup lang="ts">
import { ElButton, ElOption, ElSelect } from '@/shared/ui'
import type { Building } from '../models/weather'
import w from '../locales/zh-CN'
defineProps<{ title: string; modelValue: string; buildings: Building[]; loading?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; refresh: [] }>()
</script>
<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h1 { font-size: var(--bec-font-size-system); margin: 0; }
.controls { display: flex; align-items: center; gap: var(--bec-space-tight); }
.controls :deep(.el-select) { width: var(--bec-navigation-width); }
</style>
