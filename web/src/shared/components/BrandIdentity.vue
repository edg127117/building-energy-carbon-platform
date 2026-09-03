<script setup lang="ts">
import { t } from '@/locales'
</script>

<template>
  <div class="brand">
    <span class="brand-placeholder">{{ t('common.companyLogo') }}</span>
    <span class="brand-name">{{ t('terminology.systemName') }}</span>
  </div>
</template>

<style scoped>
.brand { display: flex; align-items: center; gap: var(--bec-space-group); min-width: 0; }
.brand-placeholder { padding: var(--bec-space-tight); border: var(--bec-border-width) dashed var(--bec-color-border); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.brand-name { font-size: var(--bec-font-size-system); color: var(--bec-color-brand-primary); font-weight: var(--bec-font-weight-heading); white-space: nowrap; }
</style>
