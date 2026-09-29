<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect } from '@/shared/ui'
import { t } from '@/locales'
import { listDeviceProducts, listEquipmentTypes, type DeviceProductListItem, type EquipmentTypeOption } from '@/modules/device-onboarding/public'
import { isHvacEquipment } from './meter/meter-display'
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

const products = ref<DeviceProductListItem[]>([])
const equipmentTypes = ref<EquipmentTypeOption[]>([])
const templateLoading = ref(false)
const templateError = ref(false)
let templateRequest = 0

watch(() => [props.open, props.equipment] as const, async ([open]) => {
  const request = ++templateRequest
  if (!open || props.equipment) return
  templateLoading.value = true
  templateError.value = false
  products.value = []
  equipmentTypes.value = []
  try {
    const types = await listEquipmentTypes()
    const items: DeviceProductListItem[] = []
    for (let page = 1; ; page++) {
      const result = await listDeviceProducts({ page, size: 100, status: 'ENABLED' })
      if (request !== templateRequest) return
      items.push(...result.items)
      if (!result.items.length || items.length >= result.total) break
    }
    if (request !== templateRequest) return
    equipmentTypes.value = types
    products.value = items.filter(item => types.some(type => type.typeCode === item.equipmentTypeCode))
  } catch {
    if (request === templateRequest) templateError.value = true
  } finally {
    if (request === templateRequest) templateLoading.value = false
  }
}, { immediate: true })

function productChanged(productId: string | undefined) {
  const product = products.value.find(item => item.productId === productId)
  form.productId = product?.productId ?? ''
  form.typeCode = product?.equipmentTypeCode ?? ''
  if (product?.manufacturer) form.manufacturer = product.manufacturer
}

function buildingChanged(buildingId: string | undefined) {
  form.spaceId = undefined
  form.systemGroupId = undefined
  emit('building-change', buildingId)
}

function submit() {
  if (!props.equipment && (templateLoading.value || templateError.value || !equipmentTypes.value.some(type => type.typeCode === form.typeCode))) return
  if (!form.buildingId || (!props.equipment && (!form.spaceId || !form.systemGroupId)) || !form.typeCode.trim() || !form.equipmentName.trim()) return
  emit('save', {
    buildingId: form.buildingId,
    spaceId: form.spaceId ?? null,
    systemGroupId: form.systemGroupId ?? null,
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
    <ElAlert v-if="templateError && !equipment" :title="t('assetManagement.equipment.templateLoadError')" type="error" :closable="false" />
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.equipmentName')" required><ElInput v-model="form.equipmentName" maxlength="100" /></ElFormItem>
      <div class="two-columns">
        <ElFormItem :label="t('assetManagement.labels.building')" required>
          <ElSelect v-model="form.buildingId" :disabled="Boolean(equipment)" class="wide-control" @change="buildingChanged">
            <ElOption v-for="item in buildings" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.equipment.productTemplate')">
          <ElInput v-if="equipment" :model-value="equipment.productName || equipment.productId || t('common.missing')" disabled />
          <ElSelect v-else v-model="form.productId" filterable clearable :loading="templateLoading" class="wide-control" :placeholder="t('assetManagement.equipment.noProductTemplate')" @change="productChanged">
            <ElOption v-for="item in products" :key="item.productId" :label="item.productName" :value="item.productId" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.equipmentType')" required>
          <ElInput v-if="equipment" :model-value="equipment.typeCode" disabled />
          <ElSelect v-else v-model="form.typeCode" filterable :disabled="Boolean(form.productId) || templateLoading || templateError" class="wide-control">
            <ElOption v-for="item in equipmentTypes" :key="item.typeCode" :label="item.typeName" :value="item.typeCode" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.space')" required>
          <ElSelect v-model="form.spaceId" :disabled="Boolean(equipment)" class="wide-control"><ElOption v-for="item in spaces" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" /></ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.system')" required>
          <ElSelect v-model="form.systemGroupId" :disabled="Boolean(equipment)" class="wide-control"><ElOption v-for="item in systemGroups" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" /></ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.manufacturer')"><ElInput v-model="form.manufacturer" maxlength="100" /></ElFormItem>
        <ElFormItem v-if="equipment && isHvacEquipment(equipment)" :label="t('assetManagement.labels.ratedCapacity')"><ElInputNumber v-model="form.ratedCapacity" disabled class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.ratedPower')"><ElInputNumber v-model="form.ratedPower" disabled class="wide-control" /></ElFormItem>
        <ElFormItem v-if="equipment && isHvacEquipment(equipment)" :label="t('assetManagement.labels.designCop')"><ElInputNumber v-model="form.designCop" disabled class="wide-control" /></ElFormItem>
      </div>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
      <ElAlert v-if="equipment" :title="t('assetManagement.associations.archiveHint')" type="info" :closable="false" />
      <ElAlert :title="t('assetManagement.forms.parametersReadOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" :disabled="!equipment && (templateLoading || templateError)" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
