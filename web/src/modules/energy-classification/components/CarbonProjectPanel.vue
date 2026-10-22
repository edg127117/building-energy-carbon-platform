<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElFormItem, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { useCarbonProject } from '../composables/use-carbon-project'
import type { CarbonProjectConfiguration, CarbonProjectEnergy, EnergyPathway } from '../api/carbon-project'
import type { ClassificationVersion } from '../models/classification'
import { localDateTimeToUtc } from '../models/classification-editor'
import CarbonImpactPreview from './CarbonImpactPreview.vue'
import ConfigurationWorkOrder from './ConfigurationWorkOrder.vue'

interface PointOption { value: string; label: string }
const props = defineProps<{
  buildingId: string
  classificationVersions: ClassificationVersion[]
  pointOptions: PointOption[]
}>()
const emit = defineEmits<{ dirtyChange: [dirty: boolean]; navigate: [domain: string] }>()
const workspace = useCarbonProject(() => props.buildingId)
const workOrderDirty = ref(false)
const reason = ref('')
const effectiveFrom = ref('')
const configuration = ref<CarbonProjectConfiguration>(emptyConfiguration())
const previewAnchor = ref<HTMLElement | null>(null)
const editable = computed(() => workspace.selectedVersion.value?.status === 'DRAFT' && !workOrderDirty.value && !workspace.loading.value && !workspace.saving.value)
const dirty = computed(() => JSON.stringify(configuration.value) !== JSON.stringify(workspace.selectedVersion.value?.configuration ?? emptyConfiguration()))
watch([dirty, workOrderDirty, workspace.saving], () => emit('dirtyChange', dirty.value || workOrderDirty.value || workspace.saving.value), { immediate: true })
const approvedClassifications = computed(() => props.classificationVersions.filter(version => version.status === 'APPROVED'))
const pathwayOptions = computed(() => workspace.options.value.map(item => item.code))
const exclusionEnergyOptions = computed(() => configuration.value.energies.filter(item => item.capability === 'ENABLED').map(item => ({ value: item.energyItemCode, label: workspace.catalog.value.find(c => c.itemCode === item.energyItemCode)?.itemName ?? item.energyItemCode })))
const exclusionPointOptions = computed(() => {
  const version = props.classificationVersions.find(v => v.versionId === configuration.value.classificationVersionId)
  const ids = new Set(version?.configuration.bindings.flatMap(b => [b.objectType === 'POINT' ? b.objectId : '', b.meterPointId ?? '']).filter(Boolean) ?? [])
  return [...ids].map(value => ({ value, label: props.pointOptions.find(p => p.value === value)?.label ?? value }))
})

watch(() => [workspace.selectedVersion.value?.versionId, workspace.selectedVersion.value?.revision], () => {
  configuration.value = workspace.selectedVersion.value ? JSON.parse(JSON.stringify(workspace.selectedVersion.value.configuration)) : emptyConfiguration()
}, { immediate: true })

function emptyConfiguration(): CarbonProjectConfiguration {
  return { classificationVersionId: null, energies: [], exclusions: [] }
}

function addEnergy() {
  configuration.value.energies.push({ energyItemCode: '', pathway: 'PURCHASED_ELECTRICITY', capability: 'ENABLED' })
}

function updateCapability(row: CarbonProjectEnergy) {
  row.capability = workspace.options.value.find(item => item.code === row.pathway)?.capability ?? 'RESERVED'
  row.energyItemCode = row.capability === 'RESERVED' ? row.pathway : ''
}
function energyOptions(pathway: EnergyPathway) {
  if (workspace.options.value.find(item => item.code === pathway)?.capability === 'RESERVED') return [{ value: pathway, label: t(`energyClassification.carbonProject.pathways.${pathway}`) }]
  const seen = new Set<string>()
  const categories = pathway === 'PURCHASED_ELECTRICITY' ? ['ELECTRICITY'] : pathway === 'PURCHASED_HEAT' ? ['HEAT'] : ['NATURAL_GAS', 'FUEL']
  return workspace.catalog.value.filter(item => item.usageScopes?.includes(pathway) && categories.includes(item.compatibleCategory ?? '')).filter(item => !seen.has(item.itemCode) && !!seen.add(item.itemCode)).map(item => ({ value: item.itemCode, label: item.itemName }))
}

function addExclusion() {
  configuration.value.exclusions.push({ energyItemCode: '', pointIds: [], reason: '', evidence: '' })
}

function removeRow<T>(rows: T[], index: number) { rows.splice(index, 1) }

function createVersion() {
  if (workOrderDirty.value) return
  void workspace.create(reason.value)
}

function saveVersion() {
  void workspace.save(configuration.value, reason.value)
}

function restoreDraft() {
  configuration.value = workspace.selectedVersion.value ? JSON.parse(JSON.stringify(workspace.selectedVersion.value.configuration)) : emptyConfiguration()
}

function runTransition(action: 'submit' | 'withdraw' | 'approve' | 'reject' | 'activate') {
  if (workOrderDirty.value || workspace.saving.value) return
  const effective = action === 'activate' ? localDateTimeToUtc(effectiveFrom.value) : undefined
  if (action === 'activate' && !effective) return
  void workspace.transition(action, reason.value, effective ?? undefined)
}

function navigateGuide(domain: string) {
  if (domain === 'SEALED_SOURCE') previewAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  else emit('navigate', domain)
}
</script>

<template>
  <section class="carbon-project-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading"><div><h2>{{ t('energyClassification.carbonProject.title') }}</h2><p>{{ t('energyClassification.carbonProject.description') }}</p></div><div class="panel-actions"><ElButton :disabled="!buildingId || dirty || workOrderDirty || workspace.loading.value || workspace.saving.value" @click="workspace.load(buildingId, workspace.selectedVersionId.value)">{{ t('energyClassification.actions.refresh') }}</ElButton><ElButton :disabled="!buildingId || dirty || workOrderDirty || !reason.trim() || workspace.loading.value || workspace.saving.value" :loading="workspace.saving.value" @click="createVersion">{{ t('energyClassification.carbonProject.create') }}</ElButton></div></div>
      </template>
      <ElAlert v-if="workspace.error.value" :title="workspace.error.value" type="error" :closable="false" show-icon />
      <ElFormItem :label="t('energyClassification.carbonProject.reason')"><ElInput v-model="reason" :placeholder="t('energyClassification.carbonProject.reasonPlaceholder')" /></ElFormItem>
      <ElEmpty v-if="!buildingId" :description="t('energyClassification.carbonProject.selectBuilding')" :image-size="48" />
      <ElEmpty v-else-if="!workspace.loading.value && !workspace.versions.value.length" :description="t('energyClassification.carbonProject.empty')" :image-size="48" />
      <template v-else-if="workspace.versions.value.length">
        <div class="project-controls">
          <ElFormItem :label="t('energyClassification.carbonProject.version')">
            <ElSelect :model-value="workspace.selectedVersionId.value" :disabled="dirty || workOrderDirty || workspace.saving.value || workspace.loading.value" filterable @update:model-value="workspace.selectVersion"><ElOption v-for="version in workspace.versions.value" :key="version.versionId" :value="version.versionId" :label="`${t('energyClassification.version.number')} ${version.versionNo} · ${t(`energyClassification.status.${version.status}`)}`" /></ElSelect>
          </ElFormItem>
          <ElFormItem v-if="workspace.selectedVersion.value?.status === 'APPROVED'" :label="t('energyClassification.carbonProject.effectiveFrom')"><input v-model="effectiveFrom" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.carbonProject.effectiveFrom')"></ElFormItem>
        </div>
        <div v-if="workspace.selectedVersion.value" class="version-meta">
          <span>{{ t('energyClassification.version.revision') }} {{ workspace.selectedVersion.value.revision }}</span>
          <ElTag>{{ t(`energyClassification.status.${workspace.selectedVersion.value.status}`) }}</ElTag>
        </div>
        <template v-if="workspace.selectedVersion.value">
          <ConfigurationWorkOrder :building-id="buildingId" :version-id="workspace.selectedVersion.value.versionId" :revision="workspace.selectedVersion.value.revision" :dirty="dirty" @dirty-change="workOrderDirty = $event" @changed="workspace.load(buildingId, workspace.selectedVersionId.value)" @navigate="navigateGuide" />
          <div class="project-actions">
            <ElButton :disabled="!editable || !reason.trim()" :loading="workspace.saving.value" @click="saveVersion">{{ t('energyClassification.actions.save') }}</ElButton>
            <ElButton v-if="dirty" :disabled="!editable" @click="restoreDraft">{{ t('energyClassification.actions.restoreDraft') }}</ElButton>
            <ElButton v-if="workspace.selectedVersion.value.status === 'DRAFT'" type="primary" :disabled="!reason.trim() || dirty || workOrderDirty || workspace.saving.value" :loading="workspace.saving.value" @click="runTransition('submit')">{{ t('energyClassification.carbonProject.submit') }}</ElButton>
            <template v-if="workspace.selectedVersion.value.status === 'PENDING_REVIEW'">
              <ElButton :disabled="!reason.trim() || workOrderDirty || workspace.saving.value" :loading="workspace.saving.value" @click="runTransition('withdraw')">{{ t('energyClassification.carbonProject.withdraw') }}</ElButton>
              <ElButton :disabled="!reason.trim() || workOrderDirty || workspace.saving.value" :loading="workspace.saving.value" @click="runTransition('reject')">{{ t('energyClassification.carbonProject.reject') }}</ElButton>
              <ElButton type="primary" :disabled="!reason.trim() || workOrderDirty || workspace.saving.value" :loading="workspace.saving.value" @click="runTransition('approve')">{{ t('energyClassification.carbonProject.approve') }}</ElButton>
            </template>
            <ElButton v-if="workspace.selectedVersion.value.status === 'APPROVED'" type="primary" :disabled="!reason.trim() || !effectiveFrom || workOrderDirty || workspace.saving.value" :loading="workspace.saving.value" @click="runTransition('activate')">{{ t('energyClassification.carbonProject.activate') }}</ElButton>
          </div>
          <ElAlert v-if="workspace.readiness.value" :title="workspace.readiness.value.ready ? t('energyClassification.carbonProject.ready') : t('energyClassification.carbonProject.notReady')" :type="workspace.readiness.value.ready ? 'success' : 'warning'" :closable="false" show-icon />
          <ul v-if="workspace.readiness.value?.issues.length" class="readiness-issues"><li v-for="(issue, index) in workspace.readiness.value.issues" :key="`${issue.code}:${index}`">{{ issue.message }}</li></ul>
          <div class="editor-section">
            <div class="section-heading"><div><h3>{{ t('energyClassification.carbonProject.boundaryTitle') }}</h3><p>{{ t('energyClassification.carbonProject.boundaryHint') }}</p></div></div>
            <ElFormItem :label="t('energyClassification.carbonProject.classificationVersion')">
              <ElSelect v-model="configuration.classificationVersionId" clearable filterable :disabled="!editable" :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="version in approvedClassifications" :key="version.versionId" :value="version.versionId" :label="`${t('energyClassification.version.number')} ${version.versionNo} · ${version.versionId}`" /></ElSelect>
            </ElFormItem>
            <div class="section-heading"><h3>{{ t('energyClassification.carbonProject.energyTitle') }}</h3><ElButton :disabled="!editable" @click="addEnergy">{{ t('energyClassification.actions.add') }}</ElButton></div>
            <ElTable v-if="configuration.energies.length" :data="configuration.energies">
              <ElTableColumn :label="t('energyClassification.fields.energyItemCode')" min-width="200"><template #default="{ row }"><ElSelect v-model="row.energyItemCode" filterable :disabled="!editable" :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in energyOptions(row.pathway)" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.carbonProject.pathway')" min-width="220"><template #default="{ row }"><ElSelect v-model="row.pathway" :disabled="!editable" @change="updateCapability(row as CarbonProjectEnergy)"><ElOption v-for="item in pathwayOptions" :key="item" :value="item" :label="t(`energyClassification.carbonProject.pathways.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.carbonProject.capability')" min-width="150"><template #default="{ row }">{{ t(`energyClassification.carbonProject.capabilities.${row.capability}`) }}</template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100"><template #default="{ $index }"><ElButton link type="danger" :disabled="!editable" @click="removeRow(configuration.energies, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.carbonProject.energyEmpty')" :image-size="40" />
          </div>
          <div class="editor-section">
            <div class="section-heading"><div><h3>{{ t('energyClassification.carbonProject.exclusionsTitle') }}</h3><p>{{ t('energyClassification.carbonProject.exclusionsHint') }}</p></div><ElButton :disabled="!editable" @click="addExclusion">{{ t('energyClassification.actions.add') }}</ElButton></div>
            <ElTable v-if="configuration.exclusions.length" :data="configuration.exclusions">
              <ElTableColumn :label="t('energyClassification.fields.energyItemCode')" min-width="200"><template #default="{ row }"><ElSelect v-model="row.energyItemCode" filterable :disabled="!editable"><ElOption v-for="item in exclusionEnergyOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.carbonProject.points')" min-width="260"><template #default="{ row }"><ElSelect v-model="row.pointIds" multiple filterable collapse-tags :disabled="!editable || !exclusionPointOptions.length" :placeholder="t('energyClassification.carbonProject.pointPlaceholder')"><ElOption v-for="point in exclusionPointOptions" :key="point.value" :value="point.value" :label="point.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.carbonProject.exclusionReason')" min-width="200"><template #default="{ row }"><ElInput v-model="row.reason" :disabled="!editable" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.evidence')" min-width="200"><template #default="{ row }"><ElInput v-model="row.evidence" :disabled="!editable" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100"><template #default="{ $index }"><ElButton link type="danger" :disabled="!editable" @click="removeRow(configuration.exclusions, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.carbonProject.exclusionsEmpty')" :image-size="40" />
          </div>
          <div ref="previewAnchor" class="editor-section"><CarbonImpactPreview :building-id="buildingId" :version-id="workspace.selectedVersion.value.versionId" :revision="workspace.selectedVersion.value.revision" :dirty="dirty" /></div>
        </template>
      </template>
    </ElCard>
  </section>
</template>

<style scoped>
.carbon-project-panel { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .section-heading, .version-meta, .project-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-actions { display: flex; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .section-heading h3 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.project-controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.version-meta, .project-actions, .editor-section { margin-top: var(--bec-space-section); }
.editor-section { display: grid; gap: var(--bec-space-group); }
.readiness-issues { color: var(--bec-color-text-secondary); }
.local-datetime-input { width: 100%; min-height: var(--bec-ref-space-40); padding-inline: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); font: inherit; }
</style>
