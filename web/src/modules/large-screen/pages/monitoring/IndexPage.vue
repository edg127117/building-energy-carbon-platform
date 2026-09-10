<script setup lang="ts">
import PendingPage from '@/shared/components/PendingPage.vue'
import { t } from '@/locales'
import SceneScreenLayout from '../../components/SceneScreenLayout.vue'
defineProps<{ title?: string }>()
</script>
<template>
  <SceneScreenLayout>
    <template #scene><div class="scene-placeholder"><PendingPage :title="t('largeScreen.scene')" /></div></template>
    <template #top><div class="summary-placeholder"><h2>{{ t('largeScreen.summary') }}</h2><span>{{ t('common.pendingTitle') }}</span></div></template>
    <template #left><PendingPage :title="t('largeScreen.leftPanel')" /></template>
    <template #right><PendingPage :title="t('largeScreen.rightPanel')" /></template>
  </SceneScreenLayout>
</template>
<style scoped>
.scene-placeholder { height: 100%; display: grid; place-items: center; text-align: center; }
.summary-placeholder { height: 100%; display: flex; align-items: center; gap: var(--bec-monitor-gap); }
h2 { margin: 0; font-size: var(--bec-monitor-font-panel); }
.summary-placeholder span { color: var(--bec-color-text-secondary); }
</style>
