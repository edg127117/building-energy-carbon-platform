<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t } from '@/locales'
import { ElAlert, ElButton, ElCard, ElEmpty, ElFormItem, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { carbonPlanApi } from '../api/carbon-plan'
import { useCarbonPlan } from '../composables/use-carbon-plan'

/** 配置平台紧凑年度入口；建筑由现有授权选择器提供，B卡片只消费已生效计划。 */
const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ dirtyChange: [dirty: boolean] }>()
const year = ref(Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(new Date())))
const sourceYear = ref(year.value - 1)
const mode = ref('FORMAL_YEAR')
const nature = ref<'EXTERNAL_CONFIRMED' | 'TEST_FIXTURE'>('EXTERNAL_CONFIRMED')
const source = ref('')
const boundary = ref('')
const reason = ref('')
const w = useCarbonPlan(() => props.buildingId, () => year.value)
const busy = computed(() => w.loading.value || w.saving.value || w.previewing.value)
const editable = computed(() => w.selected.value?.status === 'DRAFT' && !busy.value)
const dirty = computed(() => JSON.stringify(w.selected.value?.configuration ?? null) !== JSON.stringify(w.baseline.value ? { baseline: w.baseline.value, reductionPercent: w.reductionPercent.value } : null))
watch([dirty, w.saving], () => emit('dirtyChange', dirty.value || w.saving.value), { immediate: true })
watch(() => props.buildingId, () => { reason.value = ''; source.value = ''; boundary.value = '' })
function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void w.preview(id => carbonPlanApi.importPreview(id, file, nature.value, source.value, boundary.value))
  input.value = ''
}
</script>

<template>
  <ElCard class="carbon-plan-panel" shadow="never">
    <template #header><h2>{{ t('energyClassification.carbonPlan.title') }}</h2><p>{{ t('energyClassification.carbonPlan.description') }}</p></template>
    <ElAlert v-if="w.error.value" :title="w.error.value" type="error" :closable="false" />
    <div class="plan-fields">
      <ElFormItem :label="t('energyClassification.carbonPlan.year')"><ElInput v-model.number="year" type="number" min="2000" max="2200" :disabled="busy || dirty" /></ElFormItem>
      <ElFormItem :label="t('energyClassification.carbonPlan.version')"><ElSelect v-model="w.selectedId.value" :disabled="busy || dirty"><ElOption v-for="v in w.versions.value" :key="v.versionId" :value="v.versionId" :label="`${v.versionNo} · ${t(`energyClassification.carbonPlan.status.${v.status}`)}`" /></ElSelect></ElFormItem>
      <ElButton :disabled="busy || dirty || !buildingId" @click="w.load">{{ t('energyClassification.carbonPlan.refresh') }}</ElButton>
    </div>
    <ElFormItem :label="t('energyClassification.carbonPlan.reason')"><ElInput v-model="reason" maxlength="500" :disabled="busy" /></ElFormItem>
    <div class="plan-actions">
      <ElButton :disabled="busy || dirty || !buildingId || !reason.trim() || w.versions.value.some(v => ['DRAFT', 'PENDING_REVIEW', 'APPROVED'].includes(v.status))" @click="w.create(reason)">{{ t('energyClassification.carbonPlan.create') }}</ElButton>
      <ElTag v-if="w.selected.value">{{ t(`energyClassification.carbonPlan.status.${w.selected.value.status}`) }}</ElTag>
    </div>
    <ElEmpty v-if="!w.selected.value" :description="t('energyClassification.carbonPlan.empty')" :image-size="48" />
    <template v-else>
      <div v-if="w.selected.value.status === 'DRAFT'" class="plan-fields">
        <ElFormItem :label="t('energyClassification.carbonPlan.sourceMode')"><ElSelect v-model="mode" :disabled="!editable"><ElOption value="FORMAL_YEAR" :label="t('energyClassification.carbonPlan.formal')" /><ElOption value="IMPORT" :label="t('energyClassification.carbonPlan.imported')" /></ElSelect></ElFormItem>
        <template v-if="mode === 'FORMAL_YEAR'">
          <ElFormItem :label="t('energyClassification.carbonPlan.sourceYear')"><ElInput v-model.number="sourceYear" type="number" min="2000" max="2200" :disabled="!editable" /></ElFormItem>
          <ElButton :disabled="!editable" @click="w.preview(id => carbonPlanApi.baseline(id, sourceYear))">{{ t('energyClassification.carbonPlan.previewYear') }}</ElButton>
        </template>
        <template v-else>
          <ElFormItem :label="t('energyClassification.carbonPlan.nature')"><ElSelect v-model="nature" :disabled="!editable"><ElOption value="EXTERNAL_CONFIRMED" :label="t('energyClassification.carbonPlan.external')" /><ElOption value="TEST_FIXTURE" :label="t('energyClassification.carbonPlan.test')" /></ElSelect></ElFormItem>
          <ElFormItem :label="t('energyClassification.carbonPlan.source')"><ElInput v-model="source" maxlength="400" :disabled="!editable" /></ElFormItem>
          <ElFormItem :label="t('energyClassification.carbonPlan.boundary')"><ElInput v-model="boundary" maxlength="500" :disabled="!editable" /></ElFormItem>
          <label>{{ t('energyClassification.carbonPlan.upload') }}<input type="file" accept=".xlsx" :aria-label="t('energyClassification.carbonPlan.upload')" :disabled="!editable || !source.trim() || !boundary.trim()" @change="upload"></label>
          <p>{{ t('energyClassification.carbonPlan.fileHint') }}</p>
        </template>
      </div>
      <ElFormItem :label="t('energyClassification.carbonPlan.reduction')"><ElInput v-model="w.reductionPercent.value" type="number" min="0" max="100" step="0.000001" :disabled="!editable" /></ElFormItem>
      <template v-if="w.baseline.value">
        <p>{{ t('energyClassification.carbonPlan.annual') }} <strong>{{ w.baseline.value.annualBaseline }}</strong></p>
        <p>{{ t('energyClassification.carbonPlan.sourceContext', { source: w.baseline.value.source, boundary: w.baseline.value.boundary }) }}</p>
        <details>
          <summary>{{ t('energyClassification.carbonPlan.evidence') }}</summary><p>{{ w.baseline.value.evidenceHash }}</p>
          <ElTable :data="w.baseline.value.months" size="small"><ElTableColumn prop="month" :label="t('energyClassification.carbonPlan.month')" /><ElTableColumn prop="value" :label="t('energyClassification.carbonPlan.value')" /><ElTableColumn prop="source" :label="t('energyClassification.carbonPlan.source')" /><ElTableColumn prop="batchId" :label="t('energyClassification.carbonPlan.evidence')" /></ElTable>
        </details>
      </template>
      <p v-else>{{ t('energyClassification.carbonPlan.noBaseline') }}</p>
      <div class="plan-actions">
        <template v-if="w.selected.value.status === 'DRAFT'">
          <ElButton :disabled="!editable || !w.baseline.value || !w.reductionPercent.value || !reason.trim()" @click="w.save(reason)">{{ t('energyClassification.carbonPlan.save') }}</ElButton>
          <ElButton :disabled="!editable || !dirty" @click="w.restore">{{ t('energyClassification.carbonPlan.restore') }}</ElButton>
          <ElButton type="primary" :disabled="busy || dirty || !w.selected.value.configuration || !reason.trim()" @click="w.transition('submit', reason)">{{ t('energyClassification.carbonPlan.submit') }}</ElButton>
        </template>
        <template v-if="w.selected.value.status === 'PENDING_REVIEW'">
          <ElButton :disabled="busy || !reason.trim()" @click="w.transition('withdraw', reason)">{{ t('energyClassification.carbonPlan.withdraw') }}</ElButton>
          <ElButton :disabled="busy || !reason.trim()" @click="w.transition('reject', reason)">{{ t('energyClassification.carbonPlan.reject') }}</ElButton>
          <ElButton type="primary" :disabled="busy || !reason.trim()" @click="w.transition('approve', reason)">{{ t('energyClassification.carbonPlan.approve') }}</ElButton>
        </template>
        <ElButton v-if="w.selected.value.status === 'APPROVED'" type="primary" :disabled="busy || !reason.trim()" @click="w.transition('activate', reason)">{{ t('energyClassification.carbonPlan.activate') }}</ElButton>
      </div>
    </template>
    <p>{{ t('energyClassification.carbonPlan.scope') }}</p>
  </ElCard>
</template>

<style scoped>
.carbon-plan-panel h2 { margin: 0; font-size: var(--bec-font-size-title); }
.carbon-plan-panel p { color: var(--bec-color-text-secondary); overflow-wrap: anywhere; }
.plan-fields, .plan-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.plan-fields :deep(.el-form-item) { flex: 1 1 30%; min-width: 0; }
.plan-actions { margin-block: var(--bec-space-tight); }
</style>
