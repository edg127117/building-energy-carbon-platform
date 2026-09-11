<script setup lang="ts">
import { ElButton, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import type { DashboardIndicatorView } from '../mappers/hvac-dashboard'

defineProps<{ indicator: DashboardIndicatorView }>()
defineEmits<{ detail: [indicator: DashboardIndicatorView] }>()
</script>

<template>
  <article class="indicator-card">
    <div class="indicator-heading">
      <h3>{{ indicator.label }}</h3>
      <ElTag :type="indicator.status === 'SUCCESS' ? 'success' : 'warning'">{{ indicator.statusLabel }}</ElTag>
    </div>
    <p class="indicator-value"><span>{{ indicator.displayValue }}</span><small>{{ indicator.unit }}</small></p>
    <p class="indicator-summary">{{ indicator.summaryText }}</p>
    <p v-if="indicator.supportingText" class="indicator-support">{{ indicator.supportingText }}</p>
    <ElButton link type="primary" @click="$emit('detail', indicator)">{{ t('dashboard.viewCalculation') }}</ElButton>
  </article>
</template>

<style scoped>
.indicator-card { display: grid; align-content: start; gap: var(--bec-space-tight); min-width: 0; padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); }
.indicator-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-tight); }
h3, p { margin: 0; }
h3 { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-title); }
.indicator-value { display: flex; align-items: baseline; gap: var(--bec-space-tight); font-family: var(--bec-font-family-number); color: var(--bec-color-text-primary); }
.indicator-value span { font-size: var(--bec-ref-font-28); font-weight: var(--bec-font-weight-heading); }
.indicator-value small, .indicator-summary, .indicator-support { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-body); }
</style>
