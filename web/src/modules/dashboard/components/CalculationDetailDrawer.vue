<script setup lang="ts">
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDrawer, ElEmpty, ElSkeleton, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import type { useHvacCalculationDetail } from '../composables/useHvacCalculationDetail'

type CalculationDetailState = ReturnType<typeof useHvacCalculationDetail>

const props = defineProps<{ state: CalculationDetailState }>()
const emit = defineEmits<{ close: [] }>()

function statusText(status: string): string {
  const keys: Record<string, string> = {
    SUCCESS: 'dashboard.status.success',
    MISSING_INPUT: 'dashboard.status.missingInput',
    INVALID_INPUT: 'dashboard.status.invalidInput',
    ENGINE_ERROR: 'dashboard.status.calculationError',
    CALCULATION_ERROR: 'dashboard.status.calculationError',
    NO_DATA: 'dashboard.status.noData',
  }
  return t(keys[status] ?? 'dashboard.status.unknown')
}
</script>

<template>
  <ElDrawer :model-value="props.state.visible.value" :title="t('dashboard.calculationDetail')" @close="emit('close')">
    <ElSkeleton v-if="props.state.loading.value" :rows="8" animated />
    <ElAlert v-else-if="props.state.error.value" :title="props.state.error.value.message" type="error" show-icon :closable="false">
      <ElButton link type="primary" @click="props.state.retry">{{ t('dashboard.retry') }}</ElButton>
    </ElAlert>
    <ElEmpty v-else-if="props.state.localNoData.value || !props.state.detail.value" :description="t('dashboard.noCalculation')" />
    <div v-else class="detail-content">
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem :label="t('dashboard.indicatorStatus')"><ElTag>{{ statusText(props.state.detail.value.status) }}</ElTag></ElDescriptionsItem>
        <ElDescriptionsItem :label="t('dashboard.minute')">{{ formatDateTime(props.state.detail.value.minuteStart) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('dashboard.equipment')">{{ props.state.detail.value.equipId }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('dashboard.formulaVersion')">{{ props.state.detail.value.formulaVersion ?? t('common.missing') }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('dashboard.calculationResult')">{{ formatNumber(props.state.detail.value.value) }} {{ props.state.detail.value.unit ?? '' }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('dashboard.dataQuality')">{{ props.state.detail.value.dataQuality ?? t('common.missing') }}</ElDescriptionsItem>
      </ElDescriptions>
      <section>
        <h3>{{ t('dashboard.inputs') }}</h3>
        <ElTable :data="props.state.detail.value.inputs" table-layout="fixed">
          <ElTableColumn type="index" :label="t('dashboard.sequence')" />
          <ElTableColumn :label="t('dashboard.currentValue')"><template #default="{ row }">{{ formatNumber(row.value) }} {{ row.unit ?? '' }}</template></ElTableColumn>
          <ElTableColumn prop="dataQuality" :label="t('dashboard.dataQuality')" />
        </ElTable>
      </section>
      <section>
        <h3>{{ t('dashboard.steps') }}</h3>
        <ElTable :data="props.state.detail.value.steps" table-layout="fixed">
          <ElTableColumn type="index" :label="t('dashboard.sequence')" />
          <ElTableColumn prop="expression" :label="t('dashboard.expression')" />
          <ElTableColumn :label="t('dashboard.calculationResult')"><template #default="{ row }">{{ formatNumber(row.value) }} {{ row.unit ?? '' }}</template></ElTableColumn>
        </ElTable>
      </section>
    </div>
  </ElDrawer>
</template>

<style scoped>
.detail-content { display: grid; gap: var(--bec-space-section); }
h3 { margin: 0 0 var(--bec-space-tight); color: var(--bec-color-text-primary); font-size: var(--bec-font-size-title); }
</style>
