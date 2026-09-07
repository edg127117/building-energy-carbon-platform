<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElButton, ElResult } from '@/shared/ui'
import { t } from '@/locales'

const router = useRouter()
</script>

<template>
  <main class="forbidden-page" :aria-label="t('auth.forbidden.title')">
    <ElResult icon="warning" :title="t('auth.forbidden.title')" :sub-title="t('auth.forbidden.description')">
      <template #extra><ElButton type="primary" @click="router.push('/office')">{{ t('auth.forbidden.returnOffice') }}</ElButton></template>
    </ElResult>
  </main>
</template>

<style scoped>
.forbidden-page { display: grid; min-height: 100%; place-items: center; padding: var(--bec-space-page); background: var(--bec-color-page); }
</style>
