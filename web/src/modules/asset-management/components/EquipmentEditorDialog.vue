<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect } from '@/shared/ui'
import { t } from '@/locales'
import { flattenSpaces, type AssetEquipmentDetail, type AssetEquipmentForm, type AssetSpace, type AssetSystemGroup } from '../models/assets'

const props = withDefaults(defineProps<{
  open: boolean
  equipment?: AssetEquipmentDetail | null
  buildings: Array<{ label: string; value: string }>
  spaces: AssetSpace[]
  systemGroups: AssetSystemGroup[]
  submitting?: boolean
}>(), { equipment: null, submitting: false })
const emit = defineEmits<{ close: []; save: [value: AssetEquipmentForm]; 'building-change': [buildingId: string | undefined] }>()

const form = reactive({
  buildingId: undefined as string | undefined,
  spaceId: undefined as string | undefined,
  systemGroupId: undefined as string | undefined,
  typeCode: '',
  equipmentName: '',
  productId: '',
  manufacturer: '',
  ratedCapacity: undefined as number | undefined,
  ratedPower: undefined as number | undefined,
  designCop: undefined as number | undefined,
})
const title = computed(() => t(props.equipment ? 'assetManagement.forms.editEquipment' : 'assetManagement.forms.createEquipment'))
const spaces = computed(() => flattenSpaces(props.spaces))

watch(() => [props.open, props.equipment] as const, ([open]) => {
  if (!open) return
  Object.assign(form, {
    buildingId: props.equipment?.buildingId,
    spaceId: props.equipment?.spaceId ?? undefined,
    systemGroupId: props.equipment?.systemGroupId ?? undefined,
    typeCode: props.equipment?.typeCode ?? '',
    equipmentName: props.equipment?.equipmentName ?? '',
    productId: props.equipment?.productId ?? '',
    manufacturer: props.equipment?.manufacturer ?? '',
    ratedCapacity: props.equipment?.ratedCapacity ?? undefined,
    ratedPower: props.equipment?.ratedPower ?? undefined,
    designCop: props.equipment?.designCop ?? undefined,
  })
}, { immediate: true })

function buildingChanged(buildingId: string | undefined) {
  form.spaceId = undefined
  form.systemGroupId = undefined
  emit('building-change', buildingId)
}

function submit() {
  if (!form.buildingId || !form.spaceId || !form.systemGroupId || !form.typeCode.trim() || !form.equipmentName.trim()) return
  emit('save', {
    buildingId: form.buildingId,
    spaceId: form.spaceId,
    systemGroupId: form.systemGroupId,
    typeCode: form.typeCode.trim(),
    equipmentName: form.equipmentName.trim(),
    productId: nullable(form.productId),
    manufacturer: nullable(form.manufacturer),
    // 旧台账接口只接受原技术参数投影；新增保持空值，修改不得清空或覆盖治理结果。
    ratedCapacity: props.equipment?.ratedCapacity ?? null,
    ratedPower: props.equipment?.ratedPower ?? null,
    designCop: props.equipment?.designCop ?? null,
    status: 'ACTIVE',
  })
}

function nullable(value: string): string | null { return value.trim() || null }
</script>

<template>
  <ElDialog :model-value="open" :title="title" @update:model-value="emit('close')">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.equipmentName')" required><ElInput v-model="form.equipmentName" maxlength="100" /></ElFormItem>
      <div class="two-columns">
        <ElFormItem :label="t('assetManagement.labels.building')" required>
          <ElSelect v-model="form.buildingId" :disabled="Boolean(equipment)" class="wide-control" @change="buildingChanged">
            <ElOption v-for="item in buildings" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.equipmentType')" required><ElInput v-model="form.typeCode" :disabled="Boolean(equipment)" maxlength="20" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.space')" required>
          <ElSelect v-model="form.spaceId" class="wide-control"><ElOption v-for="item in spaces" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" /></ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.system')" required>
          <ElSelect v-model="form.systemGroupId" class="wide-control"><ElOption v-for="item in systemGroups" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" /></ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.productId')"><ElInput v-model="form.productId" :disabled="Boolean(equipment)" maxlength="64" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.manufacturer')"><ElInput v-model="form.manufacturer" maxlength="100" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.ratedCapacity')"><ElInputNumber v-model="form.ratedCapacity" disabled class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.ratedPower')"><ElInputNumber v-model="form.ratedPower" disabled class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.designCop')"><ElInputNumber v-model="form.designCop" disabled class="wide-control" /></ElFormItem>
      </div>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
      <ElAlert :title="t('assetManagement.forms.parametersReadOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
