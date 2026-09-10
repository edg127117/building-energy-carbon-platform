<script setup lang="ts">
import { t } from '@/locales'
defineProps<{ title: string; panel?: boolean }>()
</script>
<template>
  <section class="pending-page" :class="{ 'pending-panel': panel }" :aria-label="title"><h1>{{ title }}</h1><p>{{ t('common.pendingTitle') }}</p></section>
</template>
<style scoped>
.pending-page { min-width: 0; }
h1 { font-size: var(--bec-font-size-title); margin: 0 0 var(--bec-space-group); }
p { color: var(--bec-color-text-secondary); margin: 0; }
/* 管理端路由显式启用面板，搜索浮层和大屏仍保持轻量占位。 */
.pending-panel { height: 100%; box-sizing: border-box; display: grid; grid-template-rows: auto 1fr; padding: var(--bec-space-section); background: var(--bec-color-surface); border-radius: var(--bec-management-radius); box-shadow: var(--bec-shadow-card); }
.pending-panel h1 { overflow-wrap: anywhere; }
.pending-panel p { place-self: center; }
</style>
