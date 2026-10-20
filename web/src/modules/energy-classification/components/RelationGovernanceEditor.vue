<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElEmpty, ElForm, ElFormItem,
  ElInput, ElOption, ElSelect, ElSkeleton, ElSpace, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { useRelationGovernanceWorkspace } from '../composables/use-relation-governance-workspace'
import type { RelationMeteringAssignment, RelationMeteringBoundary, RelationMeterStructure, ReviewStatus } from '../models/classification'

type MeterRole = 'MAIN' | 'SUB' | 'INDEPENDENT' | 'UNKNOWN'
type MeterDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL' | 'UNKNOWN'
type AllocationStatus = 'ASSIGNED' | 'UNASSIGNED' | 'PENDING_EXPERT' | 'INVALID'

interface BoundaryForm {
  boundaryCode: string
  boundaryName: string
  energyType: string
  confirmationStatus: ReviewStatus
  evidenceReference: string
}

interface StructureForm {
  meteringBoundaryId: string | null
  meterPointNodeId: string
  meterRole: MeterRole
  parentMeterPointNodeId: string | null
  meterDirection: MeterDirection
  confirmationStatus: ReviewStatus
  reasonCode: string
  reasonText: string
  evidenceReference: string
  description: string
}

interface AssignmentForm {
  meteringBoundaryId: string | null
  meterPointNodeId: string | null
  targetNodeId: string | null
  allocationStatus: AllocationStatus
  reasonCode: string
  reasonText: string
  evidenceReference: string
}

const props = defineProps<{ buildingId: string }>()
const workspace = useRelationGovernanceWorkspace()
const actionReason = ref('')
const copySourceId = ref<string | null>(null)
const localError = ref('')
const editingBoundaryId = ref<string | null>(null)
const editingStructureId = ref<string | null>(null)
const editingAssignmentId = ref<string | null>(null)
const boundaryForm = ref<BoundaryForm>(emptyBoundaryForm())
const structureForm = ref<StructureForm>(emptyStructureForm())
const assignmentForm = ref<AssignmentForm>(emptyAssignmentForm())

const versionOptions = computed(() => workspace.versions.value.map(version => ({
  value: version.versionId,
  label: `${t('energyClassification.version.number')} ${version.versionNo} · ${version.status}`,
})))
const copyOptions = computed(() => workspace.versions.value.filter(version => ['EFFECTIVE', 'SUPERSEDED'].includes(version.status)))
const boundaryOptions = computed(() => workspace.boundaries.value.map(boundary => ({
  value: boundary.boundaryId,
  label: `${boundary.boundaryCode} · ${boundary.boundaryName}`,
})))
const pointOptions = computed(() => workspace.nodes.value.filter(node => node.nodeType === 'POINT' && node.status !== 'UNAVAILABLE'))
const targetOptions = computed(() => workspace.nodes.value.filter(node => node.status !== 'UNAVAILABLE'))
const pendingReviewAvailable = computed(() => Boolean(workspace.pendingReview.value))
const canCreateDraft = computed(() => Boolean(workspace.model.value && !workspace.model.value.draftVersionId))
const relationError = computed(() => {
  if (!workspace.error.value) return ''
  if (workspace.error.value.code === 'RELATION_CATALOGUE_LIMIT') return t('energyClassification.relation.catalogueLimit')
  return workspace.error.value.message
})

watch(() => props.buildingId, buildingId => { void workspace.loadBuilding(buildingId) }, { immediate: true })

function selectVersion(versionId: string | null | undefined) {
  localError.value = ''
  void workspace.selectVersion(versionId)
}

async function initialize() {
  if (!requireReason()) return
  if (await workspace.initialize(actionReason.value)) actionReason.value = ''
}

async function createDraft() {
  if (!requireReason()) return
  if (await workspace.createDraft(actionReason.value, copySourceId.value)) actionReason.value = ''
}

async function validateVersion() {
  localError.value = ''
  await workspace.validateSelected()
}

async function submitVersion() {
  if (!requireReason()) return
  if (await workspace.submitSelected(actionReason.value)) actionReason.value = ''
}

async function withdrawVersion() {
  if (!requireReason()) return
  if (await workspace.withdrawSelected(actionReason.value)) actionReason.value = ''
}

async function approveVersion() {
  if (!requireReason()) return
  if (await workspace.approvePending(actionReason.value)) actionReason.value = ''
}

async function rejectVersion() {
  if (!requireReason()) return
  if (await workspace.rejectPending(actionReason.value)) actionReason.value = ''
}

async function activateVersion() {
  if (!requireReason()) return
  if (await workspace.activateSelected(actionReason.value)) actionReason.value = ''
}

async function saveBoundary() {
  if (!boundaryForm.value.boundaryCode.trim() || !boundaryForm.value.boundaryName.trim()) {
    localError.value = t('energyClassification.relation.validation.boundary')
    return
  }
  localError.value = ''
  if (await workspace.saveBoundary({
    boundaryCode: boundaryForm.value.boundaryCode.trim(),
    boundaryName: boundaryForm.value.boundaryName.trim(),
    energyType: nullable(boundaryForm.value.energyType),
    confirmationStatus: boundaryForm.value.confirmationStatus,
    evidenceReference: nullable(boundaryForm.value.evidenceReference),
  }, editingBoundaryId.value)) resetBoundary()
}

async function retireBoundary(row: RelationMeteringBoundary) {
  localError.value = ''
  if (await workspace.retireBoundary(row.boundaryId)) resetBoundary()
}

async function saveStructure() {
  const form = structureForm.value
  if (!form.meterPointNodeId || (form.meterRole === 'SUB' && !form.parentMeterPointNodeId)) {
    localError.value = t('energyClassification.relation.validation.structure')
    return
  }
  localError.value = ''
  if (await workspace.saveStructure({
    meteringBoundaryId: form.meteringBoundaryId,
    meterPointNodeId: form.meterPointNodeId,
    meterRole: form.meterRole,
    parentMeterPointNodeId: form.parentMeterPointNodeId,
    meterDirection: form.meterDirection,
    confirmationStatus: form.confirmationStatus,
    reasonCode: nullable(form.reasonCode),
    reasonText: nullable(form.reasonText),
    evidenceReference: nullable(form.evidenceReference),
    description: nullable(form.description),
  }, editingStructureId.value)) resetStructure()
}

async function removeStructure(row: RelationMeterStructure) {
  localError.value = ''
  if (await workspace.deleteStructure(row.structureItemId)) resetStructure()
}

async function saveAssignment() {
  const form = assignmentForm.value
  if (form.allocationStatus === 'ASSIGNED' && (!form.meteringBoundaryId || !form.meterPointNodeId || !form.targetNodeId || !nullable(form.evidenceReference))) {
    localError.value = t('energyClassification.relation.validation.assignment')
    return
  }
  if (form.allocationStatus !== 'ASSIGNED' && !nullable(form.reasonCode)) {
    localError.value = t('energyClassification.relation.validation.assignmentReason')
    return
  }
  localError.value = ''
  if (await workspace.saveAssignment({
    meteringBoundaryId: form.meteringBoundaryId,
    meterPointNodeId: form.meterPointNodeId,
    targetNodeId: form.targetNodeId,
    allocationStatus: form.allocationStatus,
    reasonCode: nullable(form.reasonCode),
    reasonText: nullable(form.reasonText),
    evidenceReference: nullable(form.evidenceReference),
  }, editingAssignmentId.value)) resetAssignment()
}

async function removeAssignment(row: RelationMeteringAssignment) {
  localError.value = ''
  if (await workspace.deleteAssignment(row.assignmentItemId)) resetAssignment()
}

function editBoundary(row: RelationMeteringBoundary) {
  editingBoundaryId.value = row.boundaryId
  boundaryForm.value = {
    boundaryCode: row.boundaryCode,
    boundaryName: row.boundaryName,
    energyType: row.energyType ?? '',
    confirmationStatus: asReviewStatus(row.confirmationStatus),
    evidenceReference: row.evidenceReference ?? '',
  }
}

function editStructure(row: RelationMeterStructure) {
  editingStructureId.value = row.structureItemId
  structureForm.value = {
    meteringBoundaryId: row.meteringBoundaryId,
    meterPointNodeId: row.meterPointNodeId,
    meterRole: asMeterRole(row.meterRole),
    parentMeterPointNodeId: row.parentMeterPointNodeId,
    meterDirection: asDirection(row.meterDirection),
    confirmationStatus: asReviewStatus(row.confirmationStatus),
    reasonCode: row.reasonCode ?? '',
    reasonText: row.reasonText ?? '',
    evidenceReference: row.evidenceReference ?? '',
    description: row.description ?? '',
  }
}

function editAssignment(row: RelationMeteringAssignment) {
  editingAssignmentId.value = row.assignmentItemId
  assignmentForm.value = {
    meteringBoundaryId: row.meteringBoundaryId,
    meterPointNodeId: row.meterPointNodeId,
    targetNodeId: row.targetNodeId,
    allocationStatus: asAllocationStatus(row.allocationStatus),
    reasonCode: row.reasonCode ?? '',
    reasonText: row.reasonText ?? '',
    evidenceReference: row.evidenceReference ?? '',
  }
}

function resetBoundary() {
  editingBoundaryId.value = null
  boundaryForm.value = emptyBoundaryForm()
}

function resetStructure() {
  editingStructureId.value = null
  structureForm.value = emptyStructureForm()
}

function resetAssignment() {
  editingAssignmentId.value = null
  assignmentForm.value = emptyAssignmentForm()
}

function requireReason(): boolean {
  if (actionReason.value.trim()) return true
  localError.value = t('energyClassification.validation.reason')
  return false
}

function nodeLabel(node: { code: string | null; name: string | null; nodeId: string }): string {
  return `${node.code ?? node.nodeId} · ${node.name ?? node.nodeId}`
}

function statusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (['CONFIRMED', 'APPROVED', 'EFFECTIVE', 'ACTIVE'].includes(status)) return 'success'
  if (['PENDING_EXPERT', 'PENDING_REVIEW', 'PENDING'].includes(status)) return 'warning'
  if (['REJECTED', 'WITHDRAWN', 'INVALID'].includes(status)) return 'danger'
  return 'info'
}

function nullable(value: string): string | null {
  const trimmed = value.trim()
  return trimmed || null
}

function asReviewStatus(value: string): ReviewStatus {
  return value === 'CONFIRMED' ? 'CONFIRMED' : 'PENDING_EXPERT'
}

function asMeterRole(value: string): MeterRole {
  return value === 'MAIN' || value === 'SUB' || value === 'INDEPENDENT' ? value : 'UNKNOWN'
}

function asDirection(value: string): MeterDirection {
  return value === 'INBOUND' || value === 'OUTBOUND' || value === 'BIDIRECTIONAL' ? value : 'UNKNOWN'
}

function asAllocationStatus(value: string): AllocationStatus {
  return value === 'ASSIGNED' || value === 'UNASSIGNED' || value === 'PENDING_EXPERT' ? value : 'INVALID'
}

function emptyBoundaryForm(): BoundaryForm {
  return { boundaryCode: '', boundaryName: '', energyType: '', confirmationStatus: 'PENDING_EXPERT', evidenceReference: '' }
}

function emptyStructureForm(): StructureForm {
  return {
    meteringBoundaryId: null, meterPointNodeId: '', meterRole: 'UNKNOWN',
    parentMeterPointNodeId: null, meterDirection: 'UNKNOWN',
    confirmationStatus: 'PENDING_EXPERT', reasonCode: '', reasonText: '', evidenceReference: '', description: '',
  }
}

function emptyAssignmentForm(): AssignmentForm {
  return {
    meteringBoundaryId: null, meterPointNodeId: null, targetNodeId: null,
    allocationStatus: 'PENDING_EXPERT', reasonCode: '', reasonText: '', evidenceReference: '',
  }
}
</script>

<template>
  <ElCard shadow="never" class="relation-governance-card">
    <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.relation.governanceTitle') }}</h2><p>{{ t('energyClassification.relation.governanceDescription') }}</p></div><ElButton :disabled="!props.buildingId || workspace.loading.value || workspace.busy.value" @click="workspace.loadBuilding(props.buildingId)">{{ t('energyClassification.actions.refresh') }}</ElButton></div></template>
    <ElAlert :title="t('energyClassification.relation.nodeLabelNature')" type="info" :closable="false" show-icon />
    <ElAlert v-if="localError" :title="localError" type="error" :closable="false" show-icon />
    <ElAlert v-if="relationError" :title="relationError" type="error" :closable="false" show-icon />
    <ElSkeleton v-if="workspace.loading.value" animated :rows="4" />
    <template v-else>
      <ElForm label-position="top" class="relation-form">
        <div class="form-grid">
          <ElFormItem :label="t('energyClassification.relation.editingVersion')"><ElSelect :model-value="workspace.selectedVersionId.value ?? undefined" clearable filterable :disabled="workspace.busy.value" :loading="workspace.detailLoading.value" :placeholder="t('energyClassification.placeholders.select')" @update:model-value="selectVersion"><ElOption v-for="item in versionOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></ElFormItem>
          <ElFormItem :label="t('energyClassification.fields.reason')"><ElInput v-model="actionReason" :disabled="workspace.busy.value" :placeholder="t('energyClassification.fields.reasonPlaceholder')" clearable /></ElFormItem>
          <ElFormItem :label="t('energyClassification.relation.copySource')"><ElSelect v-model="copySourceId" clearable filterable :disabled="workspace.busy.value || !canCreateDraft" :placeholder="t('energyClassification.relation.copyDefault')"><ElOption v-for="item in copyOptions" :key="item.versionId" :value="item.versionId" :label="`${t('energyClassification.version.number')} ${item.versionNo}`" /></ElSelect></ElFormItem>
        </div>
        <ElSpace wrap class="relation-actions">
          <ElButton :disabled="!props.buildingId || Boolean(workspace.model.value) || workspace.busy.value" :loading="workspace.busy.value" @click="initialize">{{ t('energyClassification.relation.initialize') }}</ElButton>
          <ElButton :disabled="!canCreateDraft || workspace.busy.value" :loading="workspace.busy.value" @click="createDraft">{{ t('energyClassification.relation.createDraft') }}</ElButton>
          <ElButton :disabled="!workspace.selectedVersion.value || workspace.busy.value" :loading="workspace.busy.value" @click="validateVersion">{{ t('energyClassification.relation.validate') }}</ElButton>
          <ElButton type="primary" :disabled="!workspace.editable.value || workspace.busy.value" :loading="workspace.busy.value" @click="submitVersion">{{ t('energyClassification.relation.submit') }}</ElButton>
          <ElButton :disabled="!workspace.selectedVersion.value || workspace.busy.value" :loading="workspace.busy.value" @click="withdrawVersion">{{ t('energyClassification.relation.withdraw') }}</ElButton>
          <ElButton :disabled="!pendingReviewAvailable || workspace.busy.value" :loading="workspace.busy.value" @click="approveVersion">{{ t('energyClassification.relation.approve') }}</ElButton>
          <ElButton :disabled="!pendingReviewAvailable || workspace.busy.value" :loading="workspace.busy.value" @click="rejectVersion">{{ t('energyClassification.relation.reject') }}</ElButton>
          <ElButton :disabled="workspace.selectedVersion.value?.status !== 'APPROVED' || workspace.busy.value" :loading="workspace.busy.value" @click="activateVersion">{{ t('energyClassification.relation.activate') }}</ElButton>
        </ElSpace>
      </ElForm>

      <ElDescriptions v-if="workspace.selectedVersion.value" :column="3" border class="relation-details">
        <ElDescriptionsItem :label="t('energyClassification.version.number')">{{ workspace.selectedVersion.value.versionNo }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('energyClassification.version.revision')">{{ workspace.selectedVersion.value.revision }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('energyClassification.version.status')"><ElTag :type="statusType(workspace.selectedVersion.value.status)">{{ workspace.selectedVersion.value.status }}</ElTag></ElDescriptionsItem>
      </ElDescriptions>
      <ElEmpty v-else :description="t('energyClassification.relation.noVersionSelected')" :image-size="48" />
      <ElAlert v-if="workspace.validation.value" :title="t('energyClassification.relation.validationSummary', { errors: workspace.validation.value.errorCount, pending: workspace.validation.value.pendingExpertCount, warnings: workspace.validation.value.warningCount })" :type="workspace.validation.value.errorCount ? 'error' : workspace.validation.value.pendingExpertCount ? 'warning' : 'success'" :closable="false" show-icon />

      <template v-if="workspace.selectedVersion.value">
        <ElCard shadow="never" class="relation-editor-section">
          <template #header><div class="card-heading"><div><h3>{{ t('energyClassification.relation.boundaryEditor') }}</h3><p>{{ t('energyClassification.relation.boundaryEditorHint') }}</p></div><ElButton :disabled="!workspace.editable.value || workspace.busy.value" @click="resetBoundary">{{ t('energyClassification.actions.add') }}</ElButton></div></template>
          <ElForm label-position="top" class="relation-form">
            <div class="form-grid">
              <ElFormItem :label="t('energyClassification.fields.boundaryCode')"><ElInput v-model="boundaryForm.boundaryCode" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.boundaryName')"><ElInput v-model="boundaryForm.boundaryName" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.energyItemCode')"><ElInput v-model="boundaryForm.energyType" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.status')"><ElSelect v-model="boundaryForm.confirmationStatus" :disabled="!workspace.editable.value || workspace.busy.value"><ElOption value="CONFIRMED" :label="t('energyClassification.status.CONFIRMED')" /><ElOption value="PENDING_EXPERT" :label="t('energyClassification.status.PENDING_EXPERT')" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.evidence')"><ElInput v-model="boundaryForm.evidenceReference" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
            </div>
            <ElButton type="primary" :disabled="!workspace.editable.value || workspace.busy.value" :loading="workspace.busy.value" @click="saveBoundary">{{ t('energyClassification.actions.save') }}</ElButton>
          </ElForm>
          <ElTable v-if="workspace.boundaries.value.length" :data="workspace.boundaries.value" class="relation-table"><ElTableColumn prop="boundaryCode" :label="t('energyClassification.fields.boundaryCode')" min-width="140" /><ElTableColumn prop="boundaryName" :label="t('energyClassification.fields.boundaryName')" min-width="170" /><ElTableColumn prop="energyType" :label="t('energyClassification.fields.energyItemCode')" min-width="140" /><ElTableColumn prop="evidenceReference" :label="t('energyClassification.fields.evidence')" min-width="170" /><ElTableColumn :label="t('energyClassification.actions.remove')" width="150"><template #default="{ row }"><ElButton link :disabled="!workspace.editable.value || workspace.busy.value" @click="editBoundary(row as RelationMeteringBoundary)">{{ t('energyClassification.actions.edit') }}</ElButton><ElButton link type="danger" :disabled="!workspace.editable.value || workspace.busy.value" @click="retireBoundary(row as RelationMeteringBoundary)">{{ t('energyClassification.relation.retire') }}</ElButton></template></ElTableColumn></ElTable>
          <ElEmpty v-else :description="t('energyClassification.relation.noBoundaries')" :image-size="48" />
        </ElCard>

        <ElCard shadow="never" class="relation-editor-section">
          <template #header><div class="card-heading"><div><h3>{{ t('energyClassification.relation.structureEditor') }}</h3><p>{{ t('energyClassification.relation.structureEditorHint') }}</p></div><ElButton :disabled="!workspace.editable.value || workspace.busy.value" @click="resetStructure">{{ t('energyClassification.actions.add') }}</ElButton></div></template>
          <ElForm label-position="top" class="relation-form">
            <div class="form-grid">
              <ElFormItem :label="t('energyClassification.fields.boundaryId')"><ElSelect v-model="structureForm.meteringBoundaryId" clearable filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in boundaryOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.meterPointNode')"><ElSelect v-model="structureForm.meterPointNodeId" filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in pointOptions" :key="item.nodeId" :value="item.nodeId" :label="nodeLabel(item)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.meterRole')"><ElSelect v-model="structureForm.meterRole" :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in ['MAIN', 'SUB', 'INDEPENDENT', 'UNKNOWN']" :key="item" :value="item" :label="t(`energyClassification.relation.meterRole.${item}`)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.parentMeterPointCode')"><ElSelect v-model="structureForm.parentMeterPointNodeId" clearable filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in pointOptions" :key="item.nodeId" :value="item.nodeId" :label="nodeLabel(item)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.meterDirection')"><ElSelect v-model="structureForm.meterDirection" :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in ['INBOUND', 'OUTBOUND', 'BIDIRECTIONAL', 'UNKNOWN']" :key="item" :value="item" :label="t(`energyClassification.relation.direction.${item}`)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.status')"><ElSelect v-model="structureForm.confirmationStatus" :disabled="!workspace.editable.value || workspace.busy.value"><ElOption value="CONFIRMED" :label="t('energyClassification.status.CONFIRMED')" /><ElOption value="PENDING_EXPERT" :label="t('energyClassification.status.PENDING_EXPERT')" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.reasonCode')"><ElInput v-model="structureForm.reasonCode" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.reasonText')"><ElInput v-model="structureForm.reasonText" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.evidence')"><ElInput v-model="structureForm.evidenceReference" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.structureDescription')"><ElInput v-model="structureForm.description" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
            </div>
            <ElButton type="primary" :disabled="!workspace.editable.value || workspace.busy.value" :loading="workspace.busy.value" @click="saveStructure">{{ t('energyClassification.actions.save') }}</ElButton>
          </ElForm>
          <ElTable v-if="workspace.structures.value.length" :data="workspace.structures.value" class="relation-table"><ElTableColumn prop="meterPointCode" :label="t('energyClassification.fields.meterPointCode')" min-width="150" /><ElTableColumn prop="meterRole" :label="t('energyClassification.fields.meterRole')" min-width="120" /><ElTableColumn prop="parentMeterPointCode" :label="t('energyClassification.fields.parentMeterPointCode')" min-width="170" /><ElTableColumn prop="meterDirection" :label="t('energyClassification.fields.meterDirection')" min-width="140" /><ElTableColumn prop="evidenceReference" :label="t('energyClassification.fields.evidence')" min-width="170" /><ElTableColumn :label="t('energyClassification.actions.remove')" width="150"><template #default="{ row }"><ElButton link :disabled="!workspace.editable.value || workspace.busy.value" @click="editStructure(row as RelationMeterStructure)">{{ t('energyClassification.actions.edit') }}</ElButton><ElButton link type="danger" :disabled="!workspace.editable.value || workspace.busy.value" @click="removeStructure(row as RelationMeterStructure)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn></ElTable>
          <ElEmpty v-else :description="t('energyClassification.relation.noStructures')" :image-size="48" />
        </ElCard>

        <ElCard shadow="never" class="relation-editor-section">
          <template #header><div class="card-heading"><div><h3>{{ t('energyClassification.relation.assignmentEditor') }}</h3><p>{{ t('energyClassification.relation.assignmentEditorHint') }}</p></div><ElButton :disabled="!workspace.editable.value || workspace.busy.value" @click="resetAssignment">{{ t('energyClassification.actions.add') }}</ElButton></div></template>
          <ElForm label-position="top" class="relation-form">
            <div class="form-grid">
              <ElFormItem :label="t('energyClassification.fields.boundaryId')"><ElSelect v-model="assignmentForm.meteringBoundaryId" clearable filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in boundaryOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.meterPointNode')"><ElSelect v-model="assignmentForm.meterPointNodeId" clearable filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in pointOptions" :key="item.nodeId" :value="item.nodeId" :label="nodeLabel(item)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.targetNode')"><ElSelect v-model="assignmentForm.targetNodeId" clearable filterable :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in targetOptions" :key="item.nodeId" :value="item.nodeId" :label="nodeLabel(item)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.allocationStatus')"><ElSelect v-model="assignmentForm.allocationStatus" :disabled="!workspace.editable.value || workspace.busy.value"><ElOption v-for="item in ['ASSIGNED', 'UNASSIGNED', 'PENDING_EXPERT', 'INVALID']" :key="item" :value="item" :label="t(`energyClassification.relation.allocation.${item}`)" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.reasonCode')"><ElInput v-model="assignmentForm.reasonCode" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.relation.reasonText')"><ElInput v-model="assignmentForm.reasonText" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
              <ElFormItem :label="t('energyClassification.fields.evidence')"><ElInput v-model="assignmentForm.evidenceReference" :disabled="!workspace.editable.value || workspace.busy.value" /></ElFormItem>
            </div>
            <ElButton type="primary" :disabled="!workspace.editable.value || workspace.busy.value" :loading="workspace.busy.value" @click="saveAssignment">{{ t('energyClassification.actions.save') }}</ElButton>
          </ElForm>
          <ElTable v-if="workspace.assignments.value.length" :data="workspace.assignments.value" class="relation-table"><ElTableColumn prop="meteringBoundaryCode" :label="t('energyClassification.fields.boundaryCode')" min-width="150" /><ElTableColumn prop="pointCode" :label="t('energyClassification.fields.meterPointCode')" min-width="150" /><ElTableColumn prop="targetObjectName" :label="t('energyClassification.relation.targetNode')" min-width="180" /><ElTableColumn prop="allocationStatus" :label="t('energyClassification.relation.allocationStatus')" min-width="150" /><ElTableColumn prop="evidenceReference" :label="t('energyClassification.fields.evidence')" min-width="170" /><ElTableColumn :label="t('energyClassification.actions.remove')" width="150"><template #default="{ row }"><ElButton link :disabled="!workspace.editable.value || workspace.busy.value" @click="editAssignment(row as RelationMeteringAssignment)">{{ t('energyClassification.actions.edit') }}</ElButton><ElButton link type="danger" :disabled="!workspace.editable.value || workspace.busy.value" @click="removeAssignment(row as RelationMeteringAssignment)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn></ElTable>
          <ElEmpty v-else :description="t('energyClassification.relation.noAssignments')" :image-size="48" />
        </ElCard>
      </template>
    </template>
  </ElCard>
</template>

<style scoped>
.relation-governance-card, .relation-editor-section { margin-top: var(--bec-space-section); }
.card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.card-heading h2, .card-heading h3, .card-heading p { margin: 0; }
.card-heading h2, .card-heading h3 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.card-heading p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.relation-form { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.relation-form :deep(.el-form-item) { margin-bottom: 0; min-width: 0; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.relation-actions, .relation-details, .relation-table { margin-top: var(--bec-space-section); }
</style>
