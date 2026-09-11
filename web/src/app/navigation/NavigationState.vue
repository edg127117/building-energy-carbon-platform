<script setup lang="ts">
import { useRouter } from 'vue-router'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import { ElButton } from '@/shared/ui'
import { t } from '@/locales'
defineProps<{ state: 'forbidden' | 'not-found' }>()
const router = useRouter()
</script>
<template>
  <main class="navigation-state"><StateBoundary :state="state" /><ElButton @click="router.push('/systems')">{{ t('workspaces.select') }}</ElButton></main>
</template>
<style scoped>
.navigation-state { display: grid; align-content: start; gap: var(--bec-space-group); padding: var(--bec-space-page); }
</style>
