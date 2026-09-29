<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElEmpty, ElInput, ElOption, ElPagination,
  ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { flattenSpaces, type AssetSpace } from '../../models/assets'
import type { MeterCoverageView, MeterTarget } from '../../models/meter-coverage'
import { useMeterCoverage } from '../../composables/use-meter-coverage'

const props = defineProps<{ equipmentId: string; spaces: AssetSpace[] }>()
const emit = defineEmits<{ saved: [] }>()
const state = useMeterCoverage()
const spaces = computed(() => flattenSpaces(props.spaces))
const form = reactive({ installationSpaceId: null as string | null, scopeLabel: '', reason: '' })
const selectedTargetIds = ref<string[]>([])
const targetCatalog = ref(new Map<string, MeterTarget>())
const candidateQuery = reactive({ page: 1, size: 10, keyword: '' })
const activeTab = ref<'current' | 'history'>('current')
const historyPage = ref(1)
let viewGeneration = 0
const readyToSave = computed(() => form.scopeLabel.trim().length > 0 && form.reason.trim().length > 0 && !state.saveLoading.value)
const selectedTargets = computed(() => selectedTargetIds.value.map(id => targetCatalog.value.get(id)).filter((item): item is MeterTarget => Boolean(item)))

watch(() => props.equipmentId, id => { void loadFor(id) }, { immediate: true })

async function loadFor(equipmentId: string) {
  const owner = ++viewGeneration
  form.installationSpaceId = null
  form.scopeLabel = ''
  form.reason = ''
  selectedTargetIds.value = []
  targetCatalog.value = new Map()
  activeTab.value = 'current'
  candidateQuery.page = 1
  historyPage.value = 1
  try {
    const view = await state.loadCoverage(equipmentId)
    if (owner !== viewGeneration || equipmentId !== props.equipmentId) return
    applyView(view)
  } catch { /* 由受控错误状态呈现 */ }
  if (owner === viewGeneration && equipmentId === props.equipmentId) await loadCandidates(equipmentId)
}

function applyView(view: MeterCoverageView) {
  form.installationSpaceId = view.installationSpaceId
  form.scopeLabel = view.scopeLabel ?? ''
  form.reason = view.reason ?? ''
  const catalog = new Map<string, MeterTarget>()
  for (const target of view.targets) catalog.set(target.equipmentId, target)
  targetCatalog.value = catalog
  selectedTargetIds.value = view.targets.map(target => target.equipmentId)
}

async function loadCandidates(equipmentId = props.equipmentId) {
  try {
    const page = await state.loadCandidates(equipmentId, candidateQuery)
    if (equipmentId !== props.equipmentId || state.candidates.value !== page) return
    const next = new Map(targetCatalog.value)
    for (const target of page.items) next.set(target.equipmentId, target)
    targetCatalog.value = next
  } catch { /* 由受控错误状态呈现 */ }
}

async function searchCandidates() {
  candidateQuery.page = 1
  await loadCandidates()
}

async function changeCandidatePage(page: number) {
  candidateQuery.page = page
  await loadCandidates()
}

async function showHistory() {
  activeTab.value = 'history'
  await loadHistory(historyPage.value)
}

async function loadHistory(page: number) {
  historyPage.value = page
  try { await state.loadHistory(props.equipmentId, page, 10) } catch { /* 由受控错误状态呈现 */ }
}

async function save() {
  if (!readyToSave.value) return
  const equipmentId = props.equipmentId
  const owner = viewGeneration
  try {
    const saved = await state.saveCoverage(equipmentId, {
      expectedRevision: state.coverage.value?.revision ?? 0,
      installationSpaceId: form.installationSpaceId,
      scopeLabel: form.scopeLabel.trim(),
      targetEquipmentIds: [...selectedTargetIds.value],
      reason: form.reason.trim(),
    })
    if (owner !== viewGeneration || equipmentId !== props.equipmentId) return
    applyView(saved)
    emit('saved')
  } catch { /* 冲突和服务端错误留在 saveError */ }
}

function targetNames(targets: MeterTarget[]) {
  return targets.map(target => target.active
    ? target.equipmentName
    : t('assetManagement.meterCoverage.inactiveTargetDisplay', { name: target.equipmentName, status: t('assetManagement.meterCoverage.inactiveTarget') }))
    .join(t('assetManagement.meterCoverage.targetSeparator')) || t('assetManagement.meterCoverage.unconfigured')
}
</script>

<template>
  <section class="coverage-panel">
    <div class="coverage-heading">
      <div><h3>{{ t('assetManagement.meterCoverage.title') }}</h3><p>{{ t('assetManagement.meterCoverage.policy') }}</p></div>
      <div class="coverage-tabs">
        <ElButton :type="activeTab === 'current' ? 'primary' : 'default'" @click="activeTab = 'current'">{{ t('assetManagement.meterCoverage.current') }}</ElButton>
        <ElButton :type="activeTab === 'history' ? 'primary' : 'default'" @click="showHistory">{{ t('assetManagement.meterCoverage.history') }}</ElButton>
      </div>
    </div>

    <template v-if="activeTab === 'current'">
      <ElSkeleton v-if="state.coverageLoading.value" animated :rows="5" />
      <ElAlert v-else-if="state.coverageError.value" :title="state.coverageError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="state.coverage.value">
        <div class="coverage-summary">
          <div><span>{{ t('assetManagement.meterCoverage.installationLocation') }}</span><strong>{{ state.coverage.value.installationSpaceName || t('assetManagement.meterCoverage.toConfirm') }}</strong></div>
          <div><span>{{ t('assetManagement.meterCoverage.scope') }}</span><strong>{{ state.coverage.value.scopeLabel || t('assetManagement.meterCoverage.unconfigured') }}</strong></div>
          <div><span>{{ t('assetManagement.meterCoverage.targetCount') }}</span><strong>{{ state.coverage.value.targets.length }}</strong></div>
          <div><span>{{ t('assetManagement.meterCoverage.effectiveAt') }}</span><strong>{{ state.coverage.value.effectiveAt ? formatDateTime(state.coverage.value.effectiveAt) : t('common.missing') }}</strong></div>
        </div>
        <p class="policy-note">{{ t('assetManagement.meterCoverage.noAggregation') }}</p>
        <div class="coverage-form">
          <label>{{ t('assetManagement.meterCoverage.installationLocation') }}<ElSelect v-model="form.installationSpaceId" clearable filterable :placeholder="t('assetManagement.meterCoverage.toConfirm')"><ElOption v-for="space in spaces" :key="space.spaceId" :label="space.spaceName" :value="space.spaceId" /></ElSelect></label>
          <label>{{ t('assetManagement.meterCoverage.scope') }}<ElInput v-model="form.scopeLabel" maxlength="160" :placeholder="t('assetManagement.meterCoverage.scopePlaceholder')" /></label>
        </div>
        <div class="target-editor">
          <div class="section-heading"><h4>{{ t('assetManagement.meterCoverage.targets') }}</h4><span>{{ t('assetManagement.meterCoverage.selectedCount', { total: selectedTargetIds.length }) }}</span></div>
          <div class="selected-targets">
            <ElTag v-for="target in selectedTargets" :key="target.equipmentId" closable @close="selectedTargetIds = selectedTargetIds.filter(id => id !== target.equipmentId)">{{ target.equipmentName }}{{ target.active ? '' : t('assetManagement.meterCoverage.inactiveSuffix', { status: t('assetManagement.meterCoverage.inactiveTarget') }) }}</ElTag>
            <span v-if="selectedTargetIds.length === 0" class="secondary">{{ t('assetManagement.meterCoverage.unconfigured') }}</span>
          </div>
          <div class="candidate-search"><ElInput v-model="candidateQuery.keyword" clearable :placeholder="t('assetManagement.meterCoverage.searchCandidates')" @keyup.enter="searchCandidates" /><ElButton @click="searchCandidates">{{ t('assetManagement.equipment.query') }}</ElButton></div>
          <ElAlert v-if="state.candidatesError.value" :title="state.candidatesError.value.message" type="error" show-icon :closable="false" />
          <ElSkeleton v-if="state.candidatesLoading.value" animated :rows="3" />
          <ElCheckboxGroup v-else v-model="selectedTargetIds" class="candidate-list">
            <label v-for="target in state.candidates.value.items" :key="target.equipmentId" class="candidate-row"><ElCheckbox :value="target.equipmentId">{{ target.equipmentName }} <span class="secondary">{{ t('assetManagement.meterCoverage.candidateDetail', { code: target.equipmentCode || t('common.missing'), space: target.spaceName || t('common.missing') }) }}</span></ElCheckbox></label>
            <ElEmpty v-if="state.candidates.value.items.length === 0" :description="t('assetManagement.meterCoverage.noCandidates')" />
          </ElCheckboxGroup>
          <ElPagination v-if="state.candidates.value.total > candidateQuery.size" background layout="prev, pager, next" :current-page="candidateQuery.page" :page-size="candidateQuery.size" :total="state.candidates.value.total" @current-change="changeCandidatePage" />
        </div>
        <label class="reason-field">{{ t('assetManagement.meterCoverage.reason') }}<ElInput v-model="form.reason" type="textarea" maxlength="500" :rows="2" :placeholder="t('assetManagement.meterCoverage.reasonPlaceholder')" /></label>
        <ElAlert v-if="state.saveError.value" :title="state.saveError.value.message" type="error" show-icon :closable="false" />
        <div class="save-actions"><ElButton type="primary" :loading="state.saveLoading.value" :disabled="!readyToSave" @click="save">{{ t('assetManagement.actions.save') }}</ElButton></div>
      </template>
    </template>

    <template v-else>
      <ElAlert v-if="state.historyError.value" :title="state.historyError.value.message" type="error" show-icon :closable="false" />
      <ElTable v-loading="state.historyLoading.value" :data="state.history.value.items" row-key="revision">
        <ElTableColumn :label="t('assetManagement.meterCoverage.revision')" prop="revision" width="90" />
        <ElTableColumn :label="t('assetManagement.meterCoverage.effectiveAt')" min-width="170"><template #default="{ row }">{{ row.effectiveAt ? formatDateTime(row.effectiveAt) : t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.meterCoverage.installationLocation')" min-width="130"><template #default="{ row }">{{ row.installationSpaceName || t('assetManagement.meterCoverage.toConfirm') }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.meterCoverage.scope')" prop="scopeLabel" min-width="120" />
        <ElTableColumn :label="t('assetManagement.meterCoverage.targets')" min-width="200"><template #default="{ row }">{{ targetNames(row.targets) }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.meterCoverage.reason')" prop="reason" min-width="160" />
        <template #empty><ElEmpty :description="t('assetManagement.meterCoverage.emptyHistory')" /></template>
      </ElTable>
      <ElPagination v-if="state.history.value.total > 10" background layout="total, prev, pager, next" :current-page="historyPage" :page-size="10" :total="state.history.value.total" @current-change="loadHistory" />
    </template>
  </section>
</template>

<style scoped>
.coverage-panel { display: grid; gap: var(--bec-space-section); min-width: 0; }
.coverage-heading, .section-heading, .candidate-search, .save-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
h3, h4, p { margin: 0; }
h4 { font-size: var(--bec-font-size-title); }
.coverage-heading p, .secondary { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.coverage-tabs { display: flex; gap: var(--bec-space-tight); }
.coverage-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr)); gap: var(--bec-space-group); }
.coverage-summary > div { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); border-radius: var(--bec-management-radius); background: var(--bec-color-surface-secondary); }
.coverage-summary span, .coverage-form label, .reason-field { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.coverage-summary strong { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-body); overflow-wrap: anywhere; }
.policy-note { padding: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-action-primary); background: var(--bec-color-surface-secondary); }
.coverage-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.coverage-form label, .reason-field { display: grid; gap: var(--bec-space-tight); }
.target-editor { display: grid; gap: var(--bec-space-group); }
.selected-targets, .candidate-list { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.candidate-search :deep(.el-input) { flex: 1; }
.candidate-list { display: grid; gap: var(--bec-space-tight); }
.candidate-row { display: flex; align-items: center; padding: var(--bec-space-tight) var(--bec-space-group); border-radius: var(--bec-management-radius); background: var(--bec-color-surface-secondary); }
.candidate-row :deep(.el-checkbox) { width: 100%; height: auto; }
.candidate-row :deep(.el-checkbox__label) { white-space: normal; }
@media (max-width: 720px) { .coverage-heading { align-items: flex-start; flex-direction: column; } }
</style>
