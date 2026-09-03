<script setup lang="ts">
import StateBoundary from '@/shared/components/StateBoundary.vue'
import type { PageState } from '@/shared/models/page-state'
defineProps<{ title: string; state?: PageState; columns?: number }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <section class="screen-panel" :style="{ gridColumn: columns ? 'span ' + columns : undefined }" :aria-label="title">
    <h2>{{ title }}</h2>
    <StateBoundary :state="state" @retry="$emit('retry')"><slot /></StateBoundary>
    <footer v-if="$slots.footer"><slot name="footer" /></footer>
  </section>
</template>
<style scoped>
.screen-panel { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; gap: var(--bec-monitor-gap); padding: var(--bec-monitor-padding); min-width: 0; min-height: 0; background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
h2 { margin: 0; font-size: var(--bec-monitor-font-panel); overflow-wrap: anywhere; }
</style>
