<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useSession } from '@/modules/auth/public'
import { t } from '@/locales'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElOption, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag, ElTree } from '@/shared/ui'
import { useEquipmentAssociations } from '../composables/use-equipment-associations'
import type { EquipmentAssociation, RelationAction } from '../models/associations'

const state = useEquipmentAssociations()
const session = useSession()
const energyManager = computed(() => session.user?.roles.includes('ENERGY_MANAGER') === true)
const administrator = computed(() => session.user?.roles.includes('PLATFORM_ADMIN') === true)
const editable = computed(() => energyManager.value && state.version.value?.status === 'DRAFT' && typeof state.data.value?.versionRevision === 'number')
const canReview = computed(() => administrator.value && state.review.value && String(state.review.value.submittedBy) !== String(session.user?.id))
const label = (key: string) => t(`assetManagement.associations.${key}`)
const statusLabel = (status: string) => t(`assetManagement.associations.status.${status}`)
const editing = ref<EquipmentAssociation | null>(null)
const selectedSpace = ref<string>()
const selectedSystem = ref<string>()
const action = ref<RelationAction | null>(null)
const reason = ref('')
let actionKey = ''
const tree = computed(() => {
  const items = state.data.value?.spaces ?? []
  const nodes = new Map(items.map(item => [item.spaceId, { ...item, children: [] as Array<{ spaceId: string; spaceName: string }> }]))
  const roots: Array<{ spaceId: string; spaceName: string }> = []
  for (const item of items) {
    const node = nodes.get(item.spaceId)!
    const visited = new Set([item.spaceId])
    let parent = item.parentSpaceId
    while (parent && !visited.has(parent)) { visited.add(parent); parent = nodes.get(parent)?.parentSpaceId ?? null }
    // 旧投影若有循环，不递归渲染循环树；仍保留节点供定位和后端校验。
    const container = !parent && item.parentSpaceId ? nodes.get(item.parentSpaceId)?.children : undefined
    if (container) container.push(node)
    else roots.push(node)
  }
  return roots
})
function openEditor(item: EquipmentAssociation) {
  if (!editable.value || state.busy.value) return
  editing.value = item; selectedSpace.value = item.spaceId ?? undefined; selectedSystem.value = item.systemGroupId ?? undefined
}
async function save() {
  if (editing.value && await state.save(editing.value, selectedSpace.value || null, selectedSystem.value || null)) editing.value = null
}
function openAction(next: RelationAction) {
  action.value = next; reason.value = ''; actionKey = crypto.randomUUID()
}
function updateActionKey() { actionKey = crypto.randomUUID() }
async function confirmAction() {
  if (action.value && await state.act(action.value, reason.value, actionKey)) action.value = null
}
function filterSpace(id = '') {
  if (state.busy.value) return
  state.spaceId.value = id; state.unassigned.value = false; void state.query()
}
function selectTreeNode(item: { spaceId: string }) { filterSpace(item.spaceId) }
onMounted(() => state.searchBuildings())
</script>

<template>
  <section class="association-page">
    <header><h1>{{ label('title') }}</h1><p>{{ label('description') }}</p></header>
    <ElAlert v-if="state.error.value" :title="state.error.value" type="error" :closable="false" />
    <ElAlert v-if="state.notice.value" :title="state.notice.value" type="success" :closable="false" />
    <ElCard shadow="never">
      <ElForm label-position="top" class="scope-form">
        <ElFormItem :label="t('assetManagement.labels.building')">
          <ElSelect :model-value="state.buildingId.value || undefined" filterable remote :remote-method="(value: string) => state.searchBuildings(1, value)" :disabled="state.busy.value" :placeholder="label('chooseBuilding')" @change="state.selectBuilding">
            <ElOption v-for="item in state.buildings.value" :key="item.buildingId" :value="item.buildingId" :label="item.buildingName" />
            <template #footer><ElPagination small layout="prev, pager, next" :page-size="20" :total="state.buildingTotal.value" :current-page="state.buildingPage.value" @current-change="state.searchBuildings" /></template>
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="label('view')">
          <ElSelect :model-value="state.versionId.value" :disabled="state.busy.value || !state.buildingId.value" @change="state.selectVersion">
            <ElOption :value="''" :label="label('effective')" />
            <ElOption v-for="item in state.versions.value" :key="item.versionId" :value="item.versionId" :label="`${label('version')} ${item.versionNo} · ${statusLabel(item.status)}`" />
          </ElSelect>
        </ElFormItem>
        <div class="scope-actions">
          <ElButton :disabled="state.busy.value || !state.buildingId.value" @click="state.query(state.page.value)">{{ label('refresh') }}</ElButton>
          <ElButton v-if="energyManager && state.data.value && !state.model.value?.draftVersionId" type="primary" :disabled="state.busy.value" @click="openAction(state.model.value?.activeVersionId ? 'create' : 'initialize')">{{ label(state.model.value?.activeVersionId ? 'create' : 'initialize') }}</ElButton>
        </div>
      </ElForm>
      <ElAlert :title="state.versionId.value ? label('draftHint') : label('effectiveHint')" type="info" :closable="false" />
    </ElCard>
    <ElEmpty v-if="!state.buildingId.value" :description="label('chooseBuilding')" />
    <template v-else>
      <div v-loading="state.busy.value" class="association-layout">
        <ElCard shadow="never" class="space-panel">
          <template #header><h2>{{ label('spaceTree') }}</h2></template>
          <ElButton :disabled="state.busy.value" text @click="filterSpace()">{{ label('allSpaces') }}</ElButton>
          <ElTree :data="tree" node-key="spaceId" :props="{ label: 'spaceName', children: 'children' }" :current-node-key="state.spaceId.value" highlight-current default-expand-all @node-click="selectTreeNode" />
        </ElCard>
        <ElCard shadow="never" class="equipment-panel">
          <template #header><div class="list-heading"><h2>{{ label('list') }}</h2><ElTag>{{ state.version.value ? statusLabel(state.version.value.status) : label('effective') }}</ElTag></div></template>
          <form class="filters" @submit.prevent="state.query()">
            <ElInput v-model="state.keyword.value" :disabled="state.busy.value" :placeholder="t('assetManagement.equipment.keywordPlaceholder')" :aria-label="t('assetManagement.equipment.keyword')" clearable />
            <ElCheckbox v-model="state.unassigned.value" :disabled="state.busy.value" @change="state.spaceId.value = ''; state.query()">{{ label('unassignedOnly') }}</ElCheckbox>
            <ElButton native-type="submit" :disabled="state.busy.value" type="primary">{{ t('assetManagement.actions.query') }}</ElButton>
          </form>
          <p class="hint">{{ label('filterHint') }}</p>
          <ElTable :data="state.data.value?.items ?? []" row-key="equipmentId">
            <ElTableColumn prop="equipmentName" :label="t('assetManagement.labels.equipmentName')" min-width="160" />
            <ElTableColumn prop="equipmentCode" :label="t('assetManagement.labels.equipmentCode')" min-width="140" />
            <ElTableColumn :label="t('assetManagement.labels.space')" min-width="140"><template #default="{ row }">{{ row.spaceName || label('unassigned') }}</template></ElTableColumn>
            <ElTableColumn :label="t('assetManagement.equipment.system')" min-width="140"><template #default="{ row }">{{ row.systemGroupName || label('unassigned') }}</template></ElTableColumn>
            <ElTableColumn v-if="editable" :label="t('assetManagement.equipment.actions')" width="120"><template #default="{ row }"><ElButton link type="primary" :disabled="state.busy.value" @click="openEditor(row as EquipmentAssociation)">{{ label('edit') }}</ElButton></template></ElTableColumn>
          </ElTable>
          <ElPagination class="pagination" layout="total, prev, pager, next" :disabled="state.busy.value" :page-size="state.data.value?.size ?? 20" :total="state.data.value?.total ?? 0" :current-page="state.page.value" @current-change="state.query" />
        </ElCard>
      </div>
      <ElCard v-if="state.version.value && state.data.value" shadow="never">
        <template #header><h2>{{ label('workflow') }}</h2></template>
        <p>{{ state.version.value.changeReason }}</p>
        <p class="hint">{{ label('workflowHint') }}</p>
        <div class="workflow-actions">
          <ElButton :disabled="state.busy.value" @click="state.validate">{{ label('validate') }}</ElButton>
          <ElButton v-if="editable" :disabled="state.busy.value" type="primary" @click="openAction('submit')">{{ label('submit') }}</ElButton>
          <ElButton v-if="energyManager && ['DRAFT', 'PENDING_REVIEW'].includes(state.version.value.status)" :disabled="state.busy.value" @click="openAction('withdraw')">{{ label('withdraw') }}</ElButton>
          <template v-if="canReview">
            <ElButton :disabled="state.busy.value || !state.validation.value || state.validation.value.errorCount > 0 || state.validation.value.pendingExpertCount > 0 || state.diff.value?.truncated" type="primary" @click="openAction('approve')">{{ label('approve') }}</ElButton>
            <ElButton :disabled="state.busy.value" @click="openAction('reject')">{{ label('reject') }}</ElButton>
          </template>
          <ElButton v-if="administrator && state.version.value.status === 'APPROVED'" :disabled="state.busy.value" type="primary" @click="openAction('activate')">{{ label('activate') }}</ElButton>
        </div>
        <p v-if="state.latestReview.value?.reviewReason" class="hint">{{ state.latestReview.value.reviewReason }}</p>
        <div v-if="state.validation.value" class="validation">
          <p>{{ `${label('errors')} ${state.validation.value.errorCount} · ${label('pendingExpert')} ${state.validation.value.pendingExpertCount} · ${label('warnings')} ${state.validation.value.warningCount}` }}</p>
          <ul><li v-for="issue in state.validation.value.issues" :key="issue.issueId">{{ `${issue.code} · ${issue.message}` }}</li></ul>
          <template v-if="state.diff.value">
            <h3>{{ label('diff') }}</h3>
            <p>{{ `${label('added')} ${state.diff.value.addedCount} · ${label('removed')} ${state.diff.value.removedCount}` }}</p>
            <ElAlert v-if="state.diff.value.truncated" :title="label('truncated')" type="warning" :closable="false" />
            <h3>{{ label('added') }}</h3><pre>{{ state.diff.value.addedSamples.join('\n') }}</pre>
            <h3>{{ label('removed') }}</h3><pre>{{ state.diff.value.removedSamples.join('\n') }}</pre>
          </template>
          <p v-else class="hint">{{ label('initialVersion') }}</p>
        </div>
      </ElCard>
    </template>
    <ElDialog :model-value="Boolean(editing)" :title="label('edit')" :close-on-click-modal="false" :before-close="(done: () => void) => { if (!state.busy.value) { editing = null; done() } }">
      <ElAlert v-if="state.error.value" :title="state.error.value" type="error" :closable="false" />
      <p>{{ editing?.equipmentName }}</p>
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem :label="t('assetManagement.labels.space')"><ElSelect v-model="selectedSpace" filterable clearable :disabled="state.busy.value" :placeholder="label('unassigned')"><ElOption v-for="item in state.data.value?.spaces ?? []" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('assetManagement.equipment.system')"><ElSelect v-model="selectedSystem" filterable clearable :disabled="state.busy.value" :placeholder="label('unassigned')"><ElOption v-for="item in state.data.value?.systems ?? []" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" /></ElSelect></ElFormItem>
      </ElForm>
      <p class="hint">{{ label('clearHint') }}</p>
      <template #footer><ElButton :disabled="state.busy.value" @click="editing = null">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="state.busy.value" @click="save">{{ label('saveDraft') }}</ElButton></template>
    </ElDialog>
    <ElDialog :model-value="Boolean(action)" :title="action ? label(action) : ''" :close-on-click-modal="false" :before-close="(done: () => void) => { if (!state.busy.value) { action = null; done() } }">
      <ElAlert v-if="state.error.value" :title="state.error.value" type="error" :closable="false" />
      <ElAlert :title="label(action === 'activate' ? 'activateHint' : 'workflowHint')" type="warning" :closable="false" />
      <ElForm label-position="top"><ElFormItem :label="label('reason')" required><ElInput v-model="reason" type="textarea" maxlength="500" show-word-limit :disabled="state.busy.value" @input="updateActionKey" /></ElFormItem></ElForm>
      <template #footer><ElButton :disabled="state.busy.value" @click="action = null">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="state.busy.value" :disabled="!reason.trim()" @click="confirmAction">{{ label('confirm') }}</ElButton></template>
    </ElDialog>
  </section>
</template>

<style scoped>
.association-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2, h3 { font-size: var(--bec-management-title-font-size); }
header p, .hint { color: var(--bec-color-text-secondary); margin-block: var(--bec-space-tight); }
.scope-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 4)), 1fr)); gap: var(--bec-space-group); }
.scope-actions, .workflow-actions, .filters, .list-heading { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.scope-actions { align-self: center; }
.association-layout { display: flex; flex-wrap: wrap; gap: var(--bec-space-group); min-width: 0; }
.space-panel { flex: 1 1 calc(var(--bec-ref-space-64) * 3); min-width: 0; }
.equipment-panel { flex: 4 1 calc(var(--bec-ref-space-64) * 9); min-width: 0; }
.list-heading { justify-content: space-between; }
.filters :deep(.el-input) { flex: 1; min-width: min(100%, calc(var(--bec-ref-space-64) * 3)); }
.pagination { margin-top: var(--bec-space-group); overflow-x: auto; }
.workflow-actions, .validation { margin-top: var(--bec-space-group); }
.validation pre { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--bec-color-text-secondary); }
:deep(.el-select) { width: 100%; }
:deep(.el-tree-node__label) { overflow: hidden; text-overflow: ellipsis; }
</style>
