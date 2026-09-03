<script setup lang="ts">
import { ElEmpty, Info } from '@/shared/ui'
import { t } from '@/locales'
defineProps<{ title: string }>()
</script>

<template>
  <section class="pending-page" :aria-label="title">
    <h1>{{ title }}</h1>
    <ElEmpty :description="t('common.pendingTitle')">
      <template #image><Info aria-hidden="true" /></template>
      <p>{{ t('common.pendingDescription') }}</p>
    </ElEmpty>
  </section>
</template>

<style scoped>
.pending-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
h1 { font-size: var(--bec-font-size-title); margin: 0; }
p { color: var(--bec-color-text-secondary); margin: 0; max-width: var(--bec-text-measure); }
.pending-page svg { width: var(--bec-icon-large); height: var(--bec-icon-large); color: var(--bec-color-text-secondary); fill: none; }
</style>
