<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSkeleton,
} from '@/shared/ui'
import { t } from '@/locales'
import { flattenSpaces, useAssetManagement, type AssetPoint } from '@/modules/asset-management/public'
import type { DeviceProductDetail, DeviceProductListItem, PendingBindRequest, PendingDeviceDetail, PointBinding } from '../models/onboarding'

type BindingMode = 'existing' | 'new'
type BindingPointMode = 'existing' | 'new'
type BindingRow = {
  include: boolean
  mode: BindingPointMode
  existingPointId?: string
  pointCode: string
  pointName: string
  namingRuleId: string
  familyCode: string
  componentCode: string
  dataType: string
}

const props = withDefaults(defineProps<{
  open: boolean
  pending?: PendingDeviceDetail | null
  products: DeviceProductListItem[]
  product?: DeviceProductDetail | null
  productLoading?: boolean
  submitting?: boolean
}>(), { pending: null, product: null, productLoading: false, submitting: false })
const emit = defineEmits<{ close: []; 'product-change': [productId: string]; submit: [value: PendingBindRequest] }>()

const assets = useAssetManagement()
const validationKey = ref<string | null>(null)
const form = reactive({
  productId: '',
  buildingId: undefined as string | undefined,
  spaceId: undefined as string | undefined,
  systemGroupId: undefined as string | undefined,
  mode: 'existing' as BindingMode,
  existingEquipmentId: undefined as string | undefined,
  equipmentName: '',
  manufacturer: '',
  bindings: {} as Record<string, BindingRow>,
})
const productPoints = computed(() => props.product?.points.filter(point => point.enabled) ?? [])
const selectedPoints = computed<AssetPoint[]>(() => assets.points.value)
const scopeSpaces = computed(() => flattenSpaces(assets.scopeSpaces.value))

watch(() => props.open, async open => {
  if (!open) return
  reset()
  try {
    await assets.ensureBuildingOptions()
  } catch {
    // 资产模块保留建筑选项错误，表单不伪造范围数据。
  }
}, { immediate: true })

watch(() => props.product, product => {
  if (!product) return
  form.productId = product.productId
  form.bindings = Object.fromEntries(product.points.filter(point => point.enabled).map(point => [point.metricCode, emptyBinding(point.required)]))
}, { immediate: true })

function reset() {
  Object.assign(form, {
    productId: '', buildingId: undefined, spaceId: undefined, systemGroupId: undefined,
    mode: 'existing', existingEquipmentId: undefined, equipmentName: '', manufacturer: '', bindings: {},
  })
  validationKey.value = null
  void assets.loadScope(undefined)
  void assets.selectEquipment(null)
}

function changeProduct(productId: string) {
  validationKey.value = null
  emit('product-change', productId)
}

async function changeBuilding(buildingId: string | undefined) {
  form.spaceId = undefined
  form.systemGroupId = undefined
  form.existingEquipmentId = undefined
  try {
    await Promise.all([
      assets.loadScope(buildingId),
      assets.selectEquipment(null),
      assets.setEquipmentQuery({ page: 1, size: 100, buildingId, spaceId: undefined, systemGroupId: undefined }),
    ])
  } catch {
    // 资产模块保留建筑范围或设备列表的受控错误状态。
  }
}

async function changeScope() {
  form.existingEquipmentId = undefined
  try {
    await Promise.all([
      assets.selectEquipment(null),
      assets.setEquipmentQuery({
        page: 1,
        size: 100,
        buildingId: form.buildingId,
        spaceId: form.spaceId,
        systemGroupId: form.systemGroupId,
      }),
    ])
  } catch {
    // 资产模块保留设备列表的受控错误状态。
  }
}

function changeEquipmentMode() {
  form.existingEquipmentId = undefined
  void assets.selectEquipment(null)
}

async function changeEquipment(equipmentId: string | undefined) {
  form.existingEquipmentId = equipmentId
  await assets.selectEquipment(equipmentId ?? null)
}

function submit() {
  validationKey.value = validate()
  if (validationKey.value || !form.productId || !form.buildingId || !form.spaceId || !form.systemGroupId || !props.pending) return
  const pointBindings = productPoints.value.filter(point => form.bindings[point.metricCode]?.include).map(point => toBinding(point.metricCode, form.bindings[point.metricCode]))
  emit('submit', {
    productId: form.productId,
    buildingId: form.buildingId,
    spaceId: form.spaceId,
    systemGroupId: form.systemGroupId,
    existingEquipmentId: form.mode === 'existing' ? form.existingEquipmentId ?? null : null,
    newEquipment: form.mode === 'new' ? { equipmentName: form.equipmentName.trim(), manufacturer: nullable(form.manufacturer) } : null,
    pointBindings,
  })
}

function validate(): string | null {
  if (!form.productId || !props.product) return 'validation.bindingProduct'
  if (!form.buildingId || !form.spaceId || !form.systemGroupId) return 'validation.bindingScope'
  if (form.mode === 'existing' && !form.existingEquipmentId) return 'validation.bindingTarget'
  if (form.mode === 'new' && !form.equipmentName.trim()) return 'validation.bindingTarget'
  const included = productPoints.value.filter(point => form.bindings[point.metricCode]?.include)
  if (!included.length) return 'validation.bindingPoints'
  const missingRequired = productPoints.value.some(point => point.required && !validBinding(form.bindings[point.metricCode]))
  return missingRequired ? 'validation.bindingPoints' : null
}

function validBinding(row: BindingRow | undefined): boolean {
  if (!row?.include) return false
  if (bindingMode(row) === 'existing') return Boolean(row.existingPointId)
  return Boolean(row.pointCode.trim() && row.namingRuleId.trim() && row.familyCode.trim() && row.componentCode.trim() && row.dataType.trim())
}

function toBinding(metricCode: string, row: BindingRow): PointBinding {
  if (bindingMode(row) === 'existing') return { metricCode, existingPointId: row.existingPointId }
  return {
    metricCode,
    pointCode: nullable(row.pointCode),
    pointName: nullable(row.pointName),
    namingRuleId: nullable(row.namingRuleId),
    familyCode: nullable(row.familyCode),
    componentCode: nullable(row.componentCode),
    dataType: nullable(row.dataType),
  }
}

function emptyBinding(required: boolean): BindingRow {
  return { include: required, mode: 'existing', existingPointId: undefined, pointCode: '', pointName: '', namingRuleId: '', familyCode: '', componentCode: '', dataType: '' }
}

function bindingMode(row: BindingRow | undefined): BindingPointMode {
  return form.mode === 'new' ? 'new' : (row?.mode ?? 'existing')
}

function nullable(value: string): string | null { return value.trim() || null }
</script>

<template>
  <ElDialog :model-value="open" :title="t('deviceOnboarding.pending.binding')" width="80%" @update:model-value="emit('close')">
    <ElForm label-position="top" class="binding-form" @submit.prevent="submit">
      <div class="form-grid"><ElFormItem :label="t('deviceOnboarding.labels.productName')" required><ElSelect v-model="form.productId" class="wide-control" @change="changeProduct"><ElOption v-for="item in products" :key="item.productId" :label="item.productName" :value="item.productId" /></ElSelect></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.building')" required><ElSelect v-model="form.buildingId" class="wide-control" @change="changeBuilding"><ElOption v-for="item in assets.buildingOptions.value" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.space')" required><ElSelect v-model="form.spaceId" class="wide-control" @change="changeScope"><ElOption v-for="item in scopeSpaces" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" /></ElSelect></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.systemGroup')" required><ElSelect v-model="form.systemGroupId" class="wide-control" @change="changeScope"><ElOption v-for="item in assets.scopeSystemGroups.value" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" /></ElSelect></ElFormItem></div>
      <ElFormItem :label="t('deviceOnboarding.labels.targetEquipment')" required><ElRadioGroup v-model="form.mode" @change="changeEquipmentMode"><ElRadio value="existing">{{ t('deviceOnboarding.labels.existingEquipment') }}</ElRadio><ElRadio value="new">{{ t('deviceOnboarding.labels.newEquipment') }}</ElRadio></ElRadioGroup></ElFormItem>
      <template v-if="form.mode === 'existing'"><ElFormItem :label="t('deviceOnboarding.labels.existingEquipment')" required><ElSelect v-model="form.existingEquipmentId" class="wide-control" @change="changeEquipment"><ElOption v-for="item in assets.equipment.value.items" :key="item.equipmentId" :label="item.equipmentName" :value="item.equipmentId" /></ElSelect></ElFormItem></template>
      <template v-else><div class="form-grid"><ElFormItem :label="t('deviceOnboarding.labels.equipmentName')" required><ElInput v-model="form.equipmentName" maxlength="100" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.manufacturer')"><ElInput v-model="form.manufacturer" maxlength="100" /></ElFormItem></div></template>
      <ElSkeleton v-if="productLoading || assets.scopeLoading.value" animated :rows="4" />
      <ElAlert v-else-if="assets.buildingsError.value" :title="assets.buildingsError.value.message" type="error" show-icon :closable="false" />
      <ElAlert v-else-if="assets.scopeError.value" :title="assets.scopeError.value.message" type="error" show-icon :closable="false" />
      <ElAlert v-else-if="assets.equipmentError.value" :title="assets.equipmentError.value.message" type="error" show-icon :closable="false" />
      <section v-else-if="productPoints.length" class="binding-points"><h2>{{ t('deviceOnboarding.labels.pointBinding') }}</h2><div v-for="point in productPoints" :key="point.metricCode" class="binding-row"><div class="point-summary"><ElCheckbox v-model="form.bindings[point.metricCode].include" :disabled="point.required">{{ t('deviceOnboarding.labels.includePoint') }}</ElCheckbox><strong>{{ point.pointNameTemplate }}</strong><span>{{ point.unit }}</span></div><div v-if="form.bindings[point.metricCode].include" class="binding-fields"><ElFormItem v-if="form.mode === 'existing'" :label="t('deviceOnboarding.labels.bindingPointMode')"><ElRadioGroup v-model="form.bindings[point.metricCode].mode"><ElRadio value="existing">{{ t('deviceOnboarding.labels.existingPoint') }}</ElRadio><ElRadio value="new">{{ t('deviceOnboarding.labels.newPoint') }}</ElRadio></ElRadioGroup></ElFormItem><ElFormItem v-if="bindingMode(form.bindings[point.metricCode]) === 'existing'" :label="t('deviceOnboarding.labels.existingPoint')"><ElSelect v-model="form.bindings[point.metricCode].existingPointId" clearable class="wide-control"><ElOption v-for="candidate in selectedPoints" :key="candidate.pointId" :label="candidate.pointName" :value="candidate.pointId" /></ElSelect></ElFormItem><template v-else><ElFormItem :label="t('deviceOnboarding.labels.pointCode')"><ElInput v-model="form.bindings[point.metricCode].pointCode" maxlength="100" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.pointName')"><ElInput v-model="form.bindings[point.metricCode].pointName" maxlength="100" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.namingRule')"><ElInput v-model="form.bindings[point.metricCode].namingRuleId" maxlength="32" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.family')"><ElInput v-model="form.bindings[point.metricCode].familyCode" maxlength="20" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.component')"><ElInput v-model="form.bindings[point.metricCode].componentCode" maxlength="20" /></ElFormItem><ElFormItem :label="t('deviceOnboarding.labels.dataType')"><ElInput v-model="form.bindings[point.metricCode].dataType" maxlength="20" /></ElFormItem></template></div></div></section>
      <ElAlert v-if="validationKey" :title="t(`deviceOnboarding.${validationKey}`)" type="error" show-icon :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('deviceOnboarding.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('deviceOnboarding.actions.submitBinding') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.binding-form, .binding-points { display: grid; gap: var(--bec-space-section); }
.form-grid, .binding-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.binding-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: var(--bec-space-group); padding-top: var(--bec-space-group); border-top: var(--bec-border-width) solid var(--bec-color-divider); }
.point-summary { display: grid; gap: var(--bec-space-tight); align-content: start; }
.point-summary span { color: var(--bec-color-text-secondary); }
h2 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.wide-control { width: 100%; }
</style>
