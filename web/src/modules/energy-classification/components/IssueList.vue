<script setup lang="ts">
import { ElEmpty, ElTable, ElTableColumn } from '@/shared/ui'
import { t } from '@/locales'
import type { ClassificationIssue } from '../models/classification'

defineProps<{ issues: ClassificationIssue[] }>()
</script>

<template>
  <ElTable v-if="issues.length" :data="issues" class="issue-table">
    <ElTableColumn prop="severity" :label="t('energyClassification.issues.severity')" min-width="120" />
    <ElTableColumn prop="code" :label="t('energyClassification.issues.code')" min-width="180" />
    <ElTableColumn prop="reference" :label="t('energyClassification.issues.reference')" min-width="180" />
    <ElTableColumn prop="message" :label="t('energyClassification.issues.message')" min-width="260" show-overflow-tooltip />
  </ElTable>
  <ElEmpty v-else :description="t('energyClassification.issues.empty')" :image-size="48" />
</template>

<style scoped>
.issue-table { width: 100%; }
</style>
