<script setup lang="ts">
import { t } from '@/locales'
defineProps<{ title: string }>()
</script>
<template>
  <section class="pending-page" :aria-label="title"><h1>{{ title }}</h1><p>{{ t('common.pendingTitle') }}</p></section>
</template>
<style scoped>
.pending-page { min-width: 0; }
h1 { font-size: var(--bec-font-size-title); margin: 0 0 var(--bec-space-group); }
p { color: var(--bec-color-text-secondary); margin: 0; }
</style>
