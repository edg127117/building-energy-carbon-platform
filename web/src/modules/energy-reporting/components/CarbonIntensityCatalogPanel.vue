<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { useSession } from '@/modules/auth/public'
import { ElAlert, ElButton, ElCard, ElDatePicker, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElSpace, ElTable, ElTableColumn } from '@/shared/ui'
import { requestErrorMessage, requestErrorCode } from '@/shared/utils/request-error'
import { listIntensityCatalog, saveIntensityCatalog, parseIntensityRows, type IntensityCatalogSave, type IntensityCatalogVersion, type IntensityRule } from '../api/intensity-catalog'
import copy from '../locales/intensity-catalog'

const session = useSession()
const writable = computed(() => session.user?.roles.some(r => ['ENERGY_MANAGER', 'PLATFORM_ADMIN'].includes(r)) === true)
const versions = ref<IntensityCatalogVersion[]>([])
const saving = ref(false), loading = ref(false), error = ref(''), notice = ref(''), pasted = ref('')
const draft = ref<IntensityCatalogSave>(empty())
function failure(reason: unknown) {
  const code = requestErrorCode(reason)
  return code && code in copy.errors ? copy.errors[code as keyof typeof copy.errors] : requestErrorMessage(reason)
}
let generation = 0, disposed = false
let abort: AbortController | undefined
function rule(): IntensityRule {
  return { regionPrefix: '', buildingCategory: '', subcategory: null, climateLabel: '', constraintValue: '', baselineValue: '', guideValue: '',
    gasBasis: '' as IntensityRule['gasBasis'], boundaryCode: 'UNKNOWN', areaBasis: 'UNKNOWN', boundaryNote: '', areaNote: '', timeStatus: 'PENDING', applicableFrom: null, applicableTo: null }
}
function empty(): IntensityCatalogSave { return { catalogId: null, expectedVersionId: null, name: '', documentVersion: '', sourceReference: '', publishedOn: null, entries: [rule()] } }
async function load() {
  const owner = ++generation
  abort?.abort(); abort = new AbortController()
  loading.value = true; error.value = ''
  try { const result = await listIntensityCatalog(abort.signal); if (!disposed && generation === owner) versions.value = result }
  catch (e) { if (!disposed && generation === owner) error.value = failure(e) }
  finally { if (!disposed && generation === owner) loading.value = false }
}
function revise(version: IntensityCatalogVersion) {
  draft.value = { catalogId: version.catalogId, expectedVersionId: version.versionId, name: version.name,
    documentVersion: version.documentVersion, sourceReference: version.sourceReference, publishedOn: version.publishedOn,
    entries: version.entries.map(entry => ({ ...entry })) }
  notice.value = ''; error.value = ''
}
function addPasted() {
  try {
    const rows = parseIntensityRows(pasted.value, draft.value.entries[0] ?? rule())
    if (draft.value.entries.length + rows.length > 200) throw new Error('INTENSITY_IMPORT_INVALID')
    draft.value.entries.push(...rows); pasted.value = ''; error.value = ''
  } catch { error.value = copy.importInvalid }
}
function timeChanged(entry: IntensityRule) { if (entry.timeStatus !== 'KNOWN') { entry.applicableFrom = null; entry.applicableTo = null } }
async function save() {
  if (saving.value) return
  if (!draft.value.name.trim() || !draft.value.documentVersion.trim() || !draft.value.sourceReference.trim() || !draft.value.entries.length || draft.value.entries.some(r => !r.gasBasis)) { error.value = copy.missing; return }
  saving.value = true; error.value = ''; notice.value = ''
  try { const saved = await saveIntensityCatalog({ ...draft.value, entries: draft.value.entries.map(r => ({ ...r })) }); if (!disposed) { revise(saved); notice.value = copy.success; await load() } }
  catch (e) { if (!disposed) error.value = failure(e) }
  finally { if (!disposed) saving.value = false }
}
onMounted(() => { void load() })
onScopeDispose(() => { disposed = true; generation++; abort?.abort() })
</script>

<template>
  <ElCard shadow="never">
    <h2>{{ copy.title }}</h2><p>{{ copy.description }}</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElSpace><ElButton :loading="loading" :disabled="saving" @click="load">{{ copy.refresh }}</ElButton><ElButton v-if="writable" :disabled="saving" @click="draft = empty()">{{ copy.new }}</ElButton></ElSpace>
    <ElTable :data="versions" :empty-text="copy.empty">
      <ElTableColumn type="expand">
        <template #default="scope">
          <p>{{ (scope.row as IntensityCatalogVersion).sourceReference }}</p>
          <ElTable :data="(scope.row as IntensityCatalogVersion).entries">
            <ElTableColumn prop="regionPrefix" :label="copy.region" /><ElTableColumn prop="buildingCategory" :label="copy.category" />
            <ElTableColumn prop="climateLabel" :label="copy.climate" /><ElTableColumn prop="constraintValue" :label="copy.constraint" />
            <ElTableColumn prop="baselineValue" :label="copy.baseline" /><ElTableColumn prop="guideValue" :label="copy.guide" />
            <ElTableColumn :label="copy.time"><template #default="row">{{ copy[(row.row as IntensityRule).timeStatus] }}</template></ElTableColumn>
          </ElTable>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="name" :label="copy.name" /><ElTableColumn prop="documentVersion" :label="copy.documentVersion" /><ElTableColumn prop="revision" :label="copy.version" />
      <ElTableColumn v-if="writable"><template #default="scope"><ElButton :disabled="saving" @click="revise(scope.row as IntensityCatalogVersion)">{{ copy.revise }}</ElButton></template></ElTableColumn>
    </ElTable>
    <p v-if="!writable">{{ copy.readOnly }}</p>
    <ElForm v-else :disabled="saving" label-position="top" data-testid="intensity-catalog-form" @submit.prevent="save">
      <p v-if="draft.expectedVersionId">{{ copy.selected }}</p>
      <ElFormItem :label="copy.name" required><ElInput v-model="draft.name" data-test="standard-name" maxlength="200" /></ElFormItem>
      <ElFormItem :label="copy.documentVersion" required><ElInput v-model="draft.documentVersion" data-test="standard-version" maxlength="100" /></ElFormItem>
      <ElFormItem :label="copy.source" required><ElInput v-model="draft.sourceReference" data-test="standard-source" maxlength="1000" /></ElFormItem>
      <ElFormItem :label="copy.published"><ElDatePicker v-model="draft.publishedOn" value-format="YYYY-MM-DD" /></ElFormItem>
      <fieldset v-for="(entry, index) in draft.entries" :key="index">
        <legend>{{ copy.rows }} {{ index + 1 }}</legend>
        <ElFormItem :label="copy.region" required><ElInput v-model="entry.regionPrefix" data-test="rule-region" maxlength="6" :placeholder="copy.regionHint" /></ElFormItem>
        <ElFormItem :label="copy.category" required><ElInput v-model="entry.buildingCategory" data-test="rule-category" maxlength="30" /></ElFormItem>
        <ElFormItem :label="copy.climate"><ElInput v-model="entry.climateLabel" maxlength="100" /></ElFormItem>
        <ElFormItem :label="copy.gas" required><ElSelect v-model="entry.gasBasis" :placeholder="copy.unitRequired"><ElOption v-for="code in (['CO2_ONLY', 'GHG_CO2E'] as const)" :key="code" :value="code" :label="copy[code]" /></ElSelect></ElFormItem>
        <ElFormItem :label="copy.constraint" required><ElInput v-model="entry.constraintValue" data-test="rule-constraint" /></ElFormItem><ElFormItem :label="copy.baseline" required><ElInput v-model="entry.baselineValue" data-test="rule-baseline" /></ElFormItem><ElFormItem :label="copy.guide" required><ElInput v-model="entry.guideValue" data-test="rule-guide" /></ElFormItem>
        <ElFormItem :label="copy.time"><ElSelect v-model="entry.timeStatus" @change="timeChanged(entry)"><ElOption v-for="code in (['KNOWN', 'NOT_STATED', 'PENDING'] as const)" :key="code" :value="code" :label="copy[code]" /></ElSelect></ElFormItem>
        <template v-if="entry.timeStatus === 'KNOWN'"><ElFormItem :label="copy.from" required><ElDatePicker v-model="entry.applicableFrom" value-format="YYYY-MM-DD" /></ElFormItem><ElFormItem :label="copy.to"><ElDatePicker v-model="entry.applicableTo" value-format="YYYY-MM-DD" /></ElFormItem></template>
        <details>
          <summary>{{ copy.details }}</summary>
          <ElFormItem :label="copy.subcategory"><ElInput v-model="entry.subcategory" maxlength="60" /></ElFormItem>
          <ElFormItem :label="copy.boundary"><ElSelect v-model="entry.boundaryCode"><ElOption v-for="code in (['UNKNOWN', 'WHOLE_BUILDING_ENERGY', 'STANDARD_SPECIFIC'] as const)" :key="code" :value="code" :label="copy[code]" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.area"><ElSelect v-model="entry.areaBasis"><ElOption v-for="code in (['UNKNOWN', 'BUILDING_AREA'] as const)" :key="code" :value="code" :label="copy[code]" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.boundaryNote"><ElInput v-model="entry.boundaryNote" maxlength="1000" /></ElFormItem><ElFormItem :label="copy.areaNote"><ElInput v-model="entry.areaNote" maxlength="1000" /></ElFormItem>
        </details>
        <ElButton @click="draft.entries.splice(index, 1)">{{ copy.remove }}</ElButton>
      </fieldset>
      <ElSpace><ElButton :disabled="draft.entries.length >= 200" @click="draft.entries.push(rule())">{{ copy.add }}</ElButton><ElButton type="primary" :loading="saving" @click="save">{{ copy.save }}</ElButton></ElSpace>
      <ElFormItem :label="copy.import"><ElInput v-model="pasted" type="textarea" :placeholder="copy.importHint" /></ElFormItem><ElButton @click="addPasted">{{ copy.adopt }}</ElButton>
    </ElForm>
  </ElCard>
</template>

<style scoped>
fieldset {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--bec-space-group);
  min-inline-size: 0;
  margin-block: var(--bec-space-section);
  padding: var(--bec-space-group);
  border: var(--bec-border-width) solid var(--bec-color-border);
}
details { grid-column: 1 / -1; }
@media (max-width: 720px) {
  fieldset { grid-template-columns: minmax(0, 1fr); }
}
</style>
