<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElFormItem, ElTable, ElTableColumn } from '@/shared/ui'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { carbonProjectApi, type CarbonProjectImpact } from '../api/carbon-project'
import { localDateTimeToUtc } from '../models/classification-editor'

const props = defineProps<{ buildingId: string; versionId: string; revision: number; dirty: boolean }>()
const from = ref('')
const to = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<CarbonProjectImpact | null>(null)
const validRange = computed(() => {
  const start = localDateTimeToUtc(from.value)
  const end = localDateTimeToUtc(to.value)
  return start && end && start < end ? { start, end } : null
})
let generation = 0

// 修改草稿或期间即撤下旧预览；返回值只能对应本次建筑、版本和修订号。
watch(() => [props.buildingId, props.versionId, props.revision, props.dirty, from.value, to.value], () => {
  generation++
  loading.value = false
  result.value = null
  error.value = ''
})

async function preview() {
  const range = validRange.value
  if (!range || props.dirty || !props.buildingId || loading.value) return
  const owner = ++generation
  const building = props.buildingId
  const version = props.versionId
  const revision = props.revision
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const response = await carbonProjectApi.impact(building, version, range.start, range.end)
    if (owner !== generation) return
    if (response.buildingId !== building || response.versionId !== version || response.revision !== revision) {
      error.value = t('energyClassification.carbonImpact.stale')
      return
    }
    result.value = response
  } catch (reason) {
    if (owner === generation) error.value = requestErrorMessage(reason)
  } finally {
    if (owner === generation) loading.value = false
  }
}
</script>

<template>
  <section class="impact-preview">
    <div><h3>{{ t('energyClassification.carbonImpact.title') }}</h3><p>{{ t('energyClassification.carbonImpact.hint') }}</p></div>
    <ElAlert v-if="dirty" type="warning" :closable="false" :title="t('energyClassification.carbonImpact.saveFirst')" />
    <div class="impact-range">
      <ElFormItem :label="t('energyClassification.fields.startInclusive')"><input v-model="from" type="datetime-local" :aria-label="t('energyClassification.fields.startInclusive')"></ElFormItem>
      <ElFormItem :label="t('energyClassification.fields.endExclusive')"><input v-model="to" type="datetime-local" :aria-label="t('energyClassification.fields.endExclusive')"></ElFormItem>
      <ElButton :disabled="dirty || !validRange || !versionId" :loading="loading" @click="preview">{{ t('energyClassification.carbonImpact.preview') }}</ElButton>
    </div>
    <ElAlert v-if="error" type="error" :closable="false" :title="error" />
    <template v-if="result">
      <ElAlert :type="result.ready ? 'success' : 'warning'" :closable="false" :title="t(result.ready ? 'energyClassification.carbonImpact.verified' : 'energyClassification.carbonImpact.blocked')" />
      <ul v-if="result.issues.length"><li v-for="(issue, index) in result.issues" :key="index">{{ `${issue.reference} · ${issue.message}` }}</li></ul>
      <ElTable :data="result.lines">
        <ElTableColumn prop="energyItemCode" :label="t('energyClassification.fields.energyItemCode')" />
        <ElTableColumn :label="t('energyClassification.fields.status')"><template #default="{ row }">{{ t(`energyClassification.carbonImpact.status.${row.status}`) }}</template></ElTableColumn>
        <ElTableColumn prop="unit" :label="t('energyClassification.fields.unit')" />
        <ElTableColumn :label="t('energyClassification.carbonImpact.gross')"><template #default="{ row }">{{ row.grossQuantity ?? '—' }}</template></ElTableColumn>
        <ElTableColumn :label="t('energyClassification.carbonImpact.included')"><template #default="{ row }">{{ row.includedQuantity ?? '—' }}</template></ElTableColumn>
        <ElTableColumn :label="t('energyClassification.carbonImpact.excluded')"><template #default="{ row }">{{ row.excludedQuantity ?? '—' }}</template></ElTableColumn>
        <ElTableColumn :label="t('energyClassification.carbonImpact.sources')"><template #default="{ row }"><details v-if="row.excludedSources.length"><summary>{{ t('energyClassification.carbonImpact.sources') }}</summary><pre>{{ JSON.stringify(row.excludedSources, null, 2) }}</pre></details></template></ElTableColumn>
      </ElTable>
    </template>
  </section>
</template>

<style scoped>
.impact-preview { display: grid; gap: var(--bec-space-group); }
.impact-range { display: flex; gap: var(--bec-space-group); align-items: center; flex-wrap: wrap; }
.impact-preview p { color: var(--bec-color-text-secondary); }
.impact-preview pre { max-width: var(--bec-text-measure); white-space: pre-wrap; overflow-wrap: anywhere; }
.impact-preview input { width: 100%; min-height: var(--bec-ref-space-40); padding-inline: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); font: inherit; }
</style>
