<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElSpace, ElTable, ElTableColumn } from '@/shared/ui'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { relationStagingApi, type StagedAsset, type StagingOptions } from '../api/relation-staging'
import type { RelationNodeCatalogueItem } from '../models/classification'
import { stagingCopy as copy } from '../locales/relation-staging.zh-CN'

const props = defineProps<{ buildingId: string; versionId: string; revision: number; editable: boolean; nodes: RelationNodeCatalogueItem[] }>()
const emit = defineEmits<{ saved: []; dirty: [value: boolean]; busy: [value: boolean] }>()
const options = ref<StagingOptions | null>(null)
const assets = ref<StagedAsset[]>([])
const loading = ref(false), busy = ref(false), error = ref(''), reason = ref('')
const kind = ref<'EQUIPMENT' | 'POINT'>('EQUIPMENT')
const editing = ref<StagedAsset | null>(null)
const baseRevision = ref(props.revision)
const form = ref(empty())
const availableProducts = computed(() => options.value?.products.filter(p => p.code === form.value.typeCode) ?? [])
const dirty = computed(() => !!reason.value || JSON.stringify(form.value) !== JSON.stringify(empty()))
let generation = 0
function empty() { return { name: '', typeCode: '', productId: '', manufacturer: '', spaceId: '', systemGroupId: '', equipmentId: '', namingRuleId: '', pointCode: '', suffixCode: '', dataType: '', unit: '', isForCalc: 0 as 0 | 1, status: 'OFFLINE' as 'OFFLINE' | 'ONLINE' } }
const nodeOptions = (type: string) => props.nodes.filter(n => n.nodeType === type && ['ACTIVE', 'STAGED'].includes(n.status))
watch(dirty, value => emit('dirty', value))
watch(busy, value => emit('busy', value))
watch(() => form.value.typeCode, () => { form.value.productId = '' })
watch(() => [props.buildingId, props.versionId], () => {
  generation++; options.value = null; assets.value = []; error.value = ''; reset(); busy.value = false
  void load()
}, { immediate: true })
watch(() => props.revision, () => { if (!dirty.value && !busy.value) void load() })
async function load() {
  const owner = generation, building = props.buildingId, version = props.versionId
  if (!building || !version) return
  loading.value = true
  try {
    const [catalogue, list] = await Promise.all([relationStagingApi.options(building, version), relationStagingApi.list(building, version)])
    if (owner !== generation) return
    options.value = catalogue; assets.value = list.assets; baseRevision.value = list.revision
  } catch (failure) { if (owner === generation) error.value = requestErrorMessage(failure) || copy.error }
  finally { if (owner === generation) loading.value = false }
}
const nullable = (value: string) => value.trim() || null
function reset() { form.value = empty(); reason.value = ''; editing.value = null; baseRevision.value = props.revision }
function edit(asset: StagedAsset) {
  editing.value = asset; kind.value = asset.objectType
  form.value = { ...empty(), name: asset.name, spaceId: asset.spaceId ?? '', systemGroupId: asset.systemGroupId ?? '', equipmentId: asset.equipmentId ?? '' }
}
async function save() {
  if (busy.value || !props.editable) return
  const value = { ...form.value }, why = reason.value.trim()
  if (!value.name.trim() || !why || (!editing.value && (kind.value === 'EQUIPMENT' ? !value.typeCode : !value.namingRuleId || !value.dataType))) { error.value = copy.required; return }
  // Keep the revision associated with these inputs; a parent refresh must not authorize stale edits.
  const owner = generation, building = props.buildingId, version = props.versionId, revision = baseRevision.value
  busy.value = true; error.value = ''
  try {
    const base = { expectedRevision: revision, reason: why, name: value.name.trim() }
    if (editing.value) {
      await relationStagingApi.assignment(version, { expectedRevision: revision, objectType: editing.value.objectType, objectId: editing.value.objectId, spaceId: nullable(value.spaceId), systemGroupId: nullable(value.systemGroupId), equipmentId: nullable(value.equipmentId) })
      if (owner !== generation) return
      await load(); reset(); emit('saved'); return
    }
    const result = kind.value === 'EQUIPMENT'
      ? await relationStagingApi.equipment(building, version, { ...base, typeCode: value.typeCode, productId: nullable(value.productId), manufacturer: nullable(value.manufacturer), spaceId: nullable(value.spaceId), systemGroupId: nullable(value.systemGroupId) })
      : await relationStagingApi.point(building, version, { ...base, namingRuleId: value.namingRuleId, pointCode: nullable(value.pointCode), suffixCode: nullable(value.suffixCode), equipmentId: nullable(value.equipmentId), systemGroupId: nullable(value.systemGroupId), dataType: value.dataType, unit: nullable(value.unit), isForCalc: value.isForCalc, status: value.status })
    if (owner !== generation) return
    assets.value = result.assets; reset(); emit('saved')
  } catch (failure) { if (owner === generation) { error.value = requestErrorMessage(failure) || copy.error; emit('saved') } }
  finally { if (owner === generation) busy.value = false }
}
async function remove(asset: StagedAsset) {
  if (busy.value || !props.editable) return
  if (!reason.value.trim()) { error.value = copy.required; return }
  const owner = generation
  busy.value = true; error.value = ''
  try {
    const result = await relationStagingApi.remove(props.buildingId, props.versionId, asset.stagedId, baseRevision.value, reason.value.trim())
    if (owner !== generation) return
    assets.value = result.assets; reason.value = ''; emit('saved')
  } catch (failure) { if (owner === generation) { error.value = requestErrorMessage(failure) || copy.error; emit('saved') } }
  finally { if (owner === generation) busy.value = false }
}
</script>

<template>
  <ElCard class="staging-panel" :header="copy.title" shadow="never">
    <ElAlert :title="copy.note" type="info" :closable="false" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="loading">{{ copy.loading }}</p>
    <ElForm v-if="editable && options" label-position="top" :disabled="busy || loading">
      <ElSpace v-if="!editing" wrap>
        <ElButton :type="kind === 'EQUIPMENT' ? 'primary' : 'default'" @click="kind = 'EQUIPMENT'">{{ copy.equipment }}</ElButton>
        <ElButton :type="kind === 'POINT' ? 'primary' : 'default'" @click="kind = 'POINT'">{{ copy.point }}</ElButton>
      </ElSpace>
      <div class="staging-fields">
        <ElFormItem :label="copy.name"><ElInput v-model="form.name" :disabled="!!editing" maxlength="100" /></ElFormItem>
        <template v-if="kind === 'EQUIPMENT' && !editing">
          <ElFormItem :label="copy.type"><ElSelect v-model="form.typeCode" filterable><ElOption v-for="item in options.equipmentTypes" :key="item.id" :value="item.id" :label="item.name" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.product"><ElSelect v-model="form.productId" clearable filterable><ElOption v-for="item in availableProducts" :key="item.id" :value="item.id" :label="item.name" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.manufacturer"><ElInput v-model="form.manufacturer" maxlength="100" /></ElFormItem>
          <ElFormItem :label="copy.space"><ElSelect v-model="form.spaceId" clearable filterable><ElOption v-for="item in nodeOptions('SPACE')" :key="item.nodeId" :value="item.businessObjectId" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
        </template>
        <ElFormItem v-if="editing && kind === 'EQUIPMENT'" :label="copy.space"><ElSelect v-model="form.spaceId" clearable filterable><ElOption v-for="item in nodeOptions('SPACE')" :key="item.nodeId" :value="item.businessObjectId" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
        <ElFormItem v-if="editing && kind === 'POINT'" :label="copy.target"><ElSelect v-model="form.equipmentId" clearable filterable><ElOption v-for="item in nodeOptions('EQUIPMENT')" :key="item.nodeId" :value="item.businessObjectId" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
        <template v-if="kind === 'POINT' && !editing">
          <ElFormItem :label="copy.rule"><ElSelect v-model="form.namingRuleId" filterable><ElOption v-for="item in options.namingRules" :key="item.id" :value="item.id" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.target"><ElSelect v-model="form.equipmentId" clearable filterable><ElOption v-for="item in nodeOptions('EQUIPMENT')" :key="item.nodeId" :value="item.businessObjectId" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.code"><ElInput v-model="form.pointCode" maxlength="100" /></ElFormItem>
          <ElFormItem :label="copy.suffix"><ElInput v-model="form.suffixCode" maxlength="20" /></ElFormItem>
          <ElFormItem :label="copy.dataType"><ElSelect v-model="form.dataType"><ElOption value="ANALOG" :label="copy.analog" /><ElOption value="DIGITAL" :label="copy.digital" /><ElOption value="ACCUMULATE" :label="copy.accumulate" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.unit"><ElInput v-model="form.unit" maxlength="20" /></ElFormItem>
          <ElFormItem :label="copy.calculation"><ElSelect v-model="form.isForCalc"><ElOption :value="0" :label="copy.no" /><ElOption :value="1" :label="copy.yes" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.status"><ElSelect v-model="form.status"><ElOption value="OFFLINE" :label="copy.offline" /><ElOption value="ONLINE" :label="copy.online" /></ElSelect></ElFormItem>
        </template>
        <ElFormItem :label="copy.system"><ElSelect v-model="form.systemGroupId" clearable filterable><ElOption v-for="item in nodeOptions('SYSTEM')" :key="item.nodeId" :value="item.businessObjectId" :label="`${item.code} · ${item.name}`" /></ElSelect></ElFormItem>
      </div>
      <p>{{ copy.pending }}</p>
      <ElFormItem :label="copy.reason"><ElInput v-model="reason" maxlength="500" /></ElFormItem>
      <ElButton type="primary" :loading="busy" @click="save">{{ editing ? copy.saveAssignment : copy.save }}</ElButton>
      <ElButton v-if="dirty" :disabled="busy" @click="reset">{{ copy.reset }}</ElButton>
    </ElForm>
    <ElTable v-if="assets.length" :data="assets" row-key="stagedId">
      <ElTableColumn prop="code" :label="copy.codeColumn" /><ElTableColumn prop="name" :label="copy.name" />
      <ElTableColumn :label="copy.status"><template #default="{ row }">{{ row.status === 'STAGED' ? copy.staged : copy.published }}</template></ElTableColumn>
      <ElTableColumn v-if="editable" :label="copy.actions"><template #default="{ row }"><template v-if="row.status === 'STAGED'"><ElButton :disabled="busy || dirty" @click="edit(row as StagedAsset)">{{ copy.edit }}</ElButton><ElButton :disabled="busy" type="danger" plain @click="remove(row as StagedAsset)">{{ copy.cancel }}</ElButton></template></template></ElTableColumn>
    </ElTable>
    <ElEmpty v-else-if="!loading" :description="copy.empty" />
  </ElCard>
</template>

<style scoped>
.staging-panel { margin-block: var(--bec-space-4); }
.staging-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 4)), 1fr)); gap: var(--bec-space-group); margin-block-start: var(--bec-space-group); }
.staging-panel :deep(.el-select) { width: 100%; }
</style>
