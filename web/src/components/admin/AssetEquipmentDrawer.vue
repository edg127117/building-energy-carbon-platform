<template>
  <a-drawer :open="open" :title="equipment ? '编辑设备' : '新建设备'" width="500" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="设备名称" required><a-input v-model:value="form.equipmentName" maxlength="100" /></a-form-item>
      <a-form-item label="设备类型编码" required><a-input v-model:value="form.typeCode" :disabled="Boolean(equipment)" maxlength="64" /></a-form-item>
      <a-form-item label="建筑" required><a-select v-model:value="form.buildingId" :disabled="Boolean(equipment)" :options="buildings" placeholder="选择建筑" @change="onBuildingChange" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="空间" required><a-select v-model:value="form.spaceId" :options="spaceOptions" placeholder="选择空间" /></a-form-item><a-form-item label="系统分组" required><a-select v-model:value="form.systemGroupId" :options="systemGroupOptions" placeholder="选择系统分组" /></a-form-item></div>
      <div class="asset-form-grid"><a-form-item label="产品 ID"><a-input v-model:value="form.productId" :disabled="Boolean(equipment)" maxlength="64" /></a-form-item><a-form-item label="档案状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item></div>
      <a-form-item label="制造商"><a-input v-model:value="form.manufacturer" maxlength="100" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="额定容量"><a-input-number v-model:value="form.ratedCapacity" :min="0" style="width:100%" /></a-form-item><a-form-item label="额定功率"><a-input-number v-model:value="form.ratedPower" :min="0" style="width:100%" /></a-form-item></div>
      <a-form-item label="设计 COP"><a-input-number v-model:value="form.designCop" :min="0" style="width:100%" /></a-form-item>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存设备</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { AssetEquipmentDetail, AssetEquipmentForm, AssetEquipmentView, AssetSpaceView, AssetSystemGroupView } from '@/types/assets'

/** 设备表单只记录业务归属与产品关联，不承载身份绑定或测点实例化流程。 */
const props = defineProps<{
  open: boolean
  equipment?: AssetEquipmentView | AssetEquipmentDetail | null
  buildings: Array<{ label: string; value: string }>
  spaces: AssetSpaceView[]
  systemGroups: AssetSystemGroupView[]
  submitting?: boolean
}>()
const emit = defineEmits<{ close: []; save: [request: AssetEquipmentForm]; 'building-change': [buildingId: string | undefined] }>()
const form = reactive({
  equipmentName: '', buildingId: undefined as string | undefined, spaceId: undefined as string | undefined,
  systemGroupId: undefined as string | undefined, typeCode: '', productId: '', manufacturer: '',
  ratedCapacity: undefined as number | undefined, ratedPower: undefined as number | undefined,
  designCop: undefined as number | undefined, status: 'ACTIVE',
})
const statusOptions = [{ label: '启用', value: 'ACTIVE' }]
const spaceOptions = computed(() => flatten(props.spaces).map((item) => ({ label: item.spaceName, value: item.spaceId })))
const systemGroupOptions = computed(() => props.systemGroups.map((item) => ({ label: item.systemName, value: item.systemGroupId })))

watch(() => [props.open, props.equipment] as const, () => Object.assign(form, {
  equipmentName: props.equipment?.equipmentName ?? '', buildingId: props.equipment?.buildingId,
  spaceId: props.equipment?.spaceId ?? undefined, systemGroupId: props.equipment?.systemGroupId ?? undefined, typeCode: props.equipment?.typeCode ?? '',
  productId: props.equipment?.productId ?? '', manufacturer: detailValue('manufacturer'), ratedCapacity: detailNumber('ratedCapacity'),
  ratedPower: detailNumber('ratedPower'), designCop: detailNumber('designCop'), status: props.equipment?.status ?? 'ACTIVE',
}), { immediate: true })

function onBuildingChange(buildingId: string | undefined) {
  form.spaceId = undefined
  form.systemGroupId = undefined
  emit('building-change', buildingId)
}
function submit() {
  if (!form.equipmentName.trim() || !form.buildingId || !form.spaceId || !form.systemGroupId || !form.typeCode.trim()) return
  emit('save', {
    equipmentName: form.equipmentName.trim(), buildingId: form.buildingId, spaceId: form.spaceId,
    systemGroupId: form.systemGroupId, typeCode: form.typeCode.trim(), productId: nullable(form.productId),
    manufacturer: nullable(form.manufacturer), ratedCapacity: form.ratedCapacity ?? null, ratedPower: form.ratedPower ?? null,
    designCop: form.designCop ?? null, status: form.status,
  })
}
function detailValue(field: 'manufacturer') { return field in (props.equipment ?? {}) ? ((props.equipment as AssetEquipmentDetail)[field] ?? '') : '' }
function detailNumber(field: 'ratedCapacity' | 'ratedPower' | 'designCop') { return field in (props.equipment ?? {}) ? ((props.equipment as AssetEquipmentDetail)[field] ?? undefined) : undefined }
function flatten(items: AssetSpaceView[]): AssetSpaceView[] { return items.flatMap((item) => [item, ...flatten(item.children)]) }
function nullable(value: string) { return value.trim() || null }
</script>

<style scoped>.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }</style>
