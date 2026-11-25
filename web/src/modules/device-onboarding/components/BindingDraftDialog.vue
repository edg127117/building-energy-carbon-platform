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
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { flattenSpaces, useAssetManagement, type AssetPoint } from '@/modules/asset-management/public'
import { dataTypeText } from '../models/reading-labels'
import type {
  BindingProduct,
  DeviceProductListItem,
  NumericSourceOption,
  OperationsBindingOptions,
  PendingBindRequest,
  PendingDevice,
  PendingDeviceDetail,
  PointBinding,
  PointNamingRule,
  TemperatureBindingMode,
  TemperatureBindingOptions,
  TemperaturePlanSelection,
  TemperaturePlanView,
  TemperaturePreviewItem,
} from '../models/onboarding'
import { resolveIndoorBatchEquipmentNames } from '../models/indoor-batch-binding'

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
  product?: BindingProduct | null
  productLoading?: boolean
  productTotal?: number
  productPage?: number
  productSize?: number
  namingRules?: PointNamingRule[]
  namingRulesLoading?: boolean
  submitting?: boolean
  automaticMappingRejected?: boolean
  allowEmptyPoints?: boolean
  productSearchEnabled?: boolean
  numericSources?: NumericSourceOption[]
  numericSourcesLoading?: boolean
  temperatureOptions?: TemperatureBindingOptions | null
  temperatureOptionsLoading?: boolean
  temperaturePlans?: TemperaturePlanView[]
  temperaturePreviewing?: boolean
  temperaturePreviewError?: string | null
  bindingOptions?: OperationsBindingOptions | null
  batchRows?: PendingDevice[]
  submitError?: string | null
}>(), { pending: null, product: null, productLoading: false, productTotal: 0, productPage: 1, productSize: 20, namingRules: () => [], namingRulesLoading: false, submitting: false, allowEmptyPoints: false, productSearchEnabled: true, numericSources: () => [], numericSourcesLoading: false, temperatureOptions: null, temperatureOptionsLoading: false, temperaturePlans: () => [], temperaturePreviewing: false, temperaturePreviewError: null, bindingOptions: null, batchRows: () => [], submitError: null })
const emit = defineEmits<{
  close: []
  'product-change': [productId: string]
  'product-search': [keyword: string]
  'product-page-change': [page: number]
  'binding-scope-change': [spaceId?: string, systemGroupId?: string]
  'equipment-page-change': [page: number, spaceId?: string, systemGroupId?: string]
  'temperature-preview': [value: TemperaturePreviewItem]
  'temperature-preview-invalidated': []
  submit: [value: PendingBindRequest]
}>()

const assets = useAssetManagement()
const validationKey = ref<string | null>(null)
const equipmentNameTouched = ref(false)
const pointsExpanded = ref(false)
const manualPointOverride = ref(false)
const form = reactive({
  productId: '',
  buildingId: undefined as string | undefined,
  spaceId: undefined as string | undefined,
  systemGroupId: undefined as string | undefined,
  mode: 'new' as BindingMode,
  existingEquipmentId: undefined as string | undefined,
  equipmentName: '',
  manufacturer: '',
  pointCodePrefix: '',
  numericSourceId: undefined as string | undefined,
  activateOnApprove: true,
  temperatureMode: 'AUTO' as TemperatureBindingMode,
  temperatureTemplateProductId: undefined as string | undefined,
  temperatureExistingPointIds: {} as Record<string, string>,
  bindings: {} as Record<string, BindingRow>,
})
const productPoints = computed(() => props.product?.productId === form.productId ? props.product.points.filter(point => point.enabled) : [])
const batchMode = computed(() => props.batchRows.length > 1)
const batchNameMap = computed(() => resolveIndoorBatchEquipmentNames(
  props.batchRows,
  t('deviceOnboarding.messages.batchEquipmentNamePrefix'),
))
const batchPreview = computed(() => props.batchRows.map(row => ({
  pendingId: row.pendingId,
  roomCode: row.location?.roomCode ?? '',
  monitorAddress: row.location?.monitorAddress ?? '',
  assetReferenceCode: row.location?.assetReferenceCode ?? '',
  equipmentName: batchNameMap.value.get(row.pendingId) ?? '',
})))
const hasIncludedPoints = computed(() => productPoints.value.some(point => form.bindings[point.metricCode]?.include))
const temperatureEnabled = computed(() => props.allowEmptyPoints && props.pending?.identityType === 'DAIKIN_UNIT'
  && props.pending.profileCode === 'DAIKIN_INDOOR_V2')
const temperatureUsesPlan = computed(() => temperatureEnabled.value && form.temperatureMode !== 'STATE_ONLY')
const temperatureManual = computed(() => temperatureEnabled.value && form.temperatureMode === 'MANUAL')
// The server plan owns temperature points, so TypedBindRequest cannot include legacy product mappings.
const legacyPointBindingsAllowed = computed(() => !temperatureEnabled.value)
const canAutoCreatePoints = computed(() => props.pending?.identityType !== 'DAIKIN_UNIT' && productPoints.value.length > 0)
const automaticPointCreation = computed(() => canAutoCreatePoints.value && !manualPointOverride.value)
const isRadarSensor = computed(() => props.pending?.profileCode === 'MSR01_RADAR')
const isDoorSensor = computed(() => props.pending?.profileCode === 'S4_DOOR')
const doorGatewayBound = computed(() => isDoorSensor.value && Boolean(props.pending?.suggestedBuildingId && props.pending?.suggestedSpaceId))
const doorGatewayMissing = computed(() => isDoorSensor.value && !doorGatewayBound.value)
const doorGatewayLabel = computed(() => props.pending?.gatewayEquipmentName || props.pending?.gatewayMac || t('common.missing'))
const singleProductAutoMatched = computed(() => props.productTotal === 1 && Boolean(props.product && form.productId === props.product.productId))
const buildingAutoSummary = computed(() => (doorGatewayBound.value || buildingOptions.value.length === 1) && Boolean(form.buildingId))
const spaceAutoSummary = computed(() => doorGatewayBound.value && Boolean(form.spaceId))
const singleSystemAutoMatched = computed(() => scopeSystems.value.length === 1 && Boolean(form.systemGroupId))
const hasAutoSummary = computed(() => singleProductAutoMatched.value || buildingAutoSummary.value || spaceAutoSummary.value || singleSystemAutoMatched.value || isDoorSensor.value)
const selectedBuildingLabel = computed(() => buildingOptions.value.find(item => item.value === form.buildingId)?.label ?? '')
const selectedSpaceLabel = computed(() => scopeSpaces.value.find(item => item.spaceId === form.spaceId)?.spaceName ?? '')
const selectedSystemLabel = computed(() => scopeSystems.value.find(item => item.systemGroupId === form.systemGroupId)?.systemName ?? '')
const requiresNumericSource = computed(() => temperatureManual.value
  || (props.allowEmptyPoints && !temperatureEnabled.value && hasIncludedPoints.value && !automaticPointCreation.value))
const numericSourceChoices = computed(() => temperatureEnabled.value
  ? (props.temperatureOptions?.numericSources ?? []).map(source => ({ sourceId: source.sourceId, label: source.sourceName }))
  : props.numericSources.map(source => ({ sourceId: source.sourceId, label: `${source.sourceName} · ${source.sourceCode}` })))
const plannedRows = computed(() => props.temperaturePlans.filter(plan => batchMode.value
  ? props.batchRows.some(row => row.pendingId === plan.pendingId)
  : plan.pendingId === props.pending?.pendingId))
const planReady = computed(() => temperatureUsesPlan.value && plannedRows.value.length === (batchMode.value ? props.batchRows.length : 1)
  && plannedRows.value.every(plan => ['READY', 'COMPLETE'].includes(plan.status) && Boolean(plan.digest) && Boolean(plan.numericSourceId)))
const temperatureMappingPoints = ref<TemperaturePlanView['points']>([])
const buildingOptions = computed(() => props.bindingOptions
  ? [{ label: props.bindingOptions.buildingName, value: props.bindingOptions.buildingId }]
  : assets.buildingOptions.value)
const scopeSpaces = computed(() => {
  if (props.bindingOptions) return props.bindingOptions.spaces
  const spaces = flattenSpaces(assets.scopeSpaces.value)
  return isRadarSensor.value
    ? spaces.filter(space => space.spaceType?.trim().toUpperCase() === 'ROOM')
    : spaces
})
const scopeSystems = computed(() => props.bindingOptions?.systems ?? assets.scopeSystemGroups.value)
const equipmentOptions = computed(() => props.bindingOptions?.equipment ?? assets.equipment.value.items)
const selectedPoints = computed<AssetPoint[]>(() => {
  if (!props.bindingOptions) return assets.points.value
  return (props.bindingOptions.equipment.find(item => item.equipmentId === form.existingEquipmentId)?.points ?? [])
    .map(point => ({ ...point }) as AssetPoint)
})

watch(() => props.automaticMappingRejected, rejected => {
  if (rejected && props.open) manualPointOverride.value = true
})

watch(() => props.open, async open => {
  if (!open) return
  reset()
  syncProduct(props.product)
  try {
    if (!props.bindingOptions) {
      await assets.ensureBuildingOptions()
      if (doorGatewayBound.value && props.pending?.suggestedBuildingId) {
        form.buildingId = props.pending.suggestedBuildingId
        await changeBuilding(form.buildingId)
        if (props.pending.suggestedSpaceId) {
          form.spaceId = props.pending.suggestedSpaceId
          await changeScope()
        }
      } else if (!form.buildingId && buildingOptions.value.length === 1) {
        form.buildingId = buildingOptions.value[0]!.value
        await changeBuilding(form.buildingId)
      }
    }
    autoSelectSingleSystem()
    applySuggestedEquipmentName()
  } catch {
    // 资产模块保留建筑选项错误，表单不伪造范围数据。
  }
}, { immediate: true })

watch(() => props.product, product => {
  if (props.open) {
    syncProduct(product)
    applySuggestedEquipmentName()
  }
}, { immediate: true })

watch(() => scopeSystems.value, () => {
  if (props.open) autoSelectSingleSystem()
})

watch(() => form.spaceId, () => {
  if (props.open) applySuggestedEquipmentName()
})

watch(() => props.temperaturePlans, plans => {
  const plan = plans.find(item => item.pendingId === props.pending?.pendingId)
  if (plan) temperatureMappingPoints.value = plan.points
}, { deep: true })

function autoSelectSingleSystem() {
  if (!form.systemGroupId && scopeSystems.value.length === 1) {
    form.systemGroupId = scopeSystems.value[0]!.systemGroupId
  }
}

function applySuggestedEquipmentName() {
  if (batchMode.value || form.mode !== 'new' || equipmentNameTouched.value) return
  const productName = props.product?.productName?.trim()
  if (!productName) return
  const spaceName = scopeSpaces.value.find(item => item.spaceId === form.spaceId)?.spaceName?.trim()
  if (spaceName) {
    form.equipmentName = `${spaceName}-${productName}`
    return
  }
  const suffix = props.pending?.identityValue?.slice(-4)
  form.equipmentName = suffix ? `${productName}-${suffix}` : productName
}

function syncProduct(product: BindingProduct | null | undefined) {
  invalidateTemperaturePreview()
  if (!product) {
    form.productId = ''
    form.bindings = {}
    return
  }
  form.productId = product.productId
  form.bindings = Object.fromEntries(product.points.filter(point => point.enabled).map(point => [
    point.metricCode,
    emptyBinding(point.required, point.pointNameTemplate, point.suffixCode, point.dataType),
  ]))
}

function reset() {
  Object.assign(form, {
    productId: '', buildingId: props.bindingOptions?.buildingId, spaceId: props.batchRows[0]?.location?.roomSpaceId, systemGroupId: undefined,
    mode: 'new', existingEquipmentId: undefined, equipmentName: batchMode.value ? t('deviceOnboarding.messages.batchEquipmentNamePrefix') : '', manufacturer: '', pointCodePrefix: '', numericSourceId: undefined, activateOnApprove: true, bindings: {},
    temperatureMode: 'AUTO', temperatureTemplateProductId: undefined, temperatureExistingPointIds: {},
  })
  equipmentNameTouched.value = false
  pointsExpanded.value = false
  manualPointOverride.value = false
  temperatureMappingPoints.value = []
  validationKey.value = null
  if (!props.bindingOptions) {
    void assets.loadScope(undefined)
    void assets.selectEquipment(null)
  }
}

function changeProduct(productId: string) {
  invalidateTemperaturePreview()
  validationKey.value = null
  form.productId = productId
  form.bindings = {}
  form.spaceId = props.batchRows[0]?.location?.roomSpaceId
  form.systemGroupId = undefined
  form.existingEquipmentId = undefined
  form.temperatureExistingPointIds = {}
  temperatureMappingPoints.value = []
  clearExistingPointSelections()
  if (!props.bindingOptions) void assets.selectEquipment(null)
  emit('product-change', productId)
}

function applyPointCodePrefix() {
  for (const point of productPoints.value) {
    const row = form.bindings[point.metricCode]
    if (row && bindingMode(row) === 'new') row.pointCode = joinPointCode(form.pointCodePrefix, point.suffixCode)
  }
}

function changeNamingRule(metricCode: string, ruleId: string) {
  const row = form.bindings[metricCode]
  const rule = props.namingRules.find(item => item.ruleId === ruleId)
  if (!row || !rule) return
  row.namingRuleId = rule.ruleId
  row.familyCode = rule.familyCode
  row.componentCode = rule.componentCode
}

async function changeBuilding(buildingId: string | undefined) {
  invalidateTemperaturePreview()
  form.spaceId = undefined
  form.systemGroupId = undefined
  form.existingEquipmentId = undefined
  clearExistingPointSelections()
  if (props.bindingOptions) {
    emit('binding-scope-change', form.spaceId, form.systemGroupId)
    return
  }
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
  invalidateTemperaturePreview()
  form.existingEquipmentId = undefined
  clearExistingPointSelections()
  if (props.bindingOptions) {
    emit('binding-scope-change', form.spaceId, form.systemGroupId)
    return
  }
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
  invalidateTemperaturePreview()
  form.existingEquipmentId = undefined
  clearExistingPointSelections()
  if (!props.bindingOptions) void assets.selectEquipment(null)
}

async function changeEquipment(equipmentId: string | undefined) {
  invalidateTemperaturePreview()
  form.existingEquipmentId = equipmentId
  clearExistingPointSelections()
  if (!props.bindingOptions) await assets.selectEquipment(equipmentId ?? null)
}

function clearExistingPointSelections() {
  for (const row of Object.values(form.bindings)) row.existingPointId = undefined
}

function changeTemperatureMode() {
  form.temperatureTemplateProductId = undefined
  form.numericSourceId = undefined
  form.temperatureExistingPointIds = {}
  temperatureMappingPoints.value = []
  invalidateTemperaturePreview()
}

function changeTemperatureTemplate() {
  form.temperatureExistingPointIds = {}
  temperatureMappingPoints.value = []
  invalidateTemperaturePreview()
}

function changeTemperatureSource() {
  invalidateTemperaturePreview()
}

function changeTemperatureExistingPoint() {
  invalidateTemperaturePreview()
}

function invalidateTemperaturePreview() {
  if (temperatureEnabled.value) emit('temperature-preview-invalidated')
}

function submit() {
  validationKey.value = validate(true)
  if (validationKey.value || !form.productId || !form.buildingId || !form.spaceId || !form.systemGroupId || !props.pending) return
  emit('submit', bindingRequest())
}

function previewTemperature() {
  validationKey.value = validate(false)
  if (validationKey.value || !props.pending || !temperatureUsesPlan.value) return
  emit('temperature-preview', {
    pendingId: props.pending.pendingId,
    ...temperatureSelection(),
    binding: bindingRequest(),
  })
}

function bindingRequest(): PendingBindRequest {
  const pointBindings = automaticPointCreation.value || !legacyPointBindingsAllowed.value
    ? []
    : productPoints.value.filter(point => form.bindings[point.metricCode]?.include).map(point => toBinding(point.metricCode, form.bindings[point.metricCode]))
  return {
    productId: form.productId,
    buildingId: form.buildingId,
    spaceId: form.spaceId,
    systemGroupId: form.systemGroupId,
    existingEquipmentId: form.mode === 'existing' ? form.existingEquipmentId ?? null : null,
    newEquipment: form.mode === 'new' ? { equipmentName: form.equipmentName.trim(), manufacturer: nullable(form.manufacturer) } : null,
    pointBindings,
    autoCreatePoints: automaticPointCreation.value,
    activateOnApprove: form.activateOnApprove,
    ...(isDoorSensor.value && props.pending?.gatewayMac ? { gatewayMac: props.pending.gatewayMac } : {}),
    numericSourceId: requiresNumericSource.value ? form.numericSourceId ?? null : null,
    temperatureMode: temperatureEnabled.value ? form.temperatureMode : undefined,
    temperatureTemplateProductId: temperatureManual.value ? form.temperatureTemplateProductId ?? null : undefined,
    temperatureExistingPointIds: temperatureEnabled.value && Object.keys(form.temperatureExistingPointIds).length
      ? { ...form.temperatureExistingPointIds } : null,
  }
}

function temperatureSelection(): TemperaturePlanSelection {
  const selected = Object.entries(form.temperatureExistingPointIds)
    .filter(([, pointId]) => Boolean(pointId))
  return {
    mode: form.temperatureMode,
    ...(temperatureManual.value && form.temperatureTemplateProductId ? { templateProductId: form.temperatureTemplateProductId } : {}),
    ...(temperatureManual.value && form.numericSourceId ? { numericSourceId: form.numericSourceId } : {}),
    ...(selected.length ? { existingPointIds: Object.fromEntries(selected) } : {}),
  }
}

function validate(requireTemperaturePlan: boolean): string | null {
  if (doorGatewayMissing.value) return 'validation.doorGatewayRequired'
  if (!form.productId || !props.product) return 'validation.bindingProduct'
  if (!form.buildingId || !form.spaceId || !form.systemGroupId) return 'validation.bindingScope'
  if (batchMode.value && props.batchRows.some(row => !row.location?.roomSpaceId || !row.location.roomCode)) return 'validation.batchLocation'
  if (batchMode.value && productPoints.value.length > 0 && legacyPointBindingsAllowed.value) return 'validation.batchPoints'
  if (form.mode === 'existing' && !form.existingEquipmentId) return 'validation.bindingTarget'
  if (form.mode === 'new' && !form.equipmentName.trim()) return 'validation.bindingTarget'
  if (temperatureManual.value && !form.temperatureTemplateProductId) return 'validation.temperatureTemplate'
  if (temperatureManual.value && !form.numericSourceId) return 'validation.temperatureNumericSource'
  if (automaticPointCreation.value) return null
  const included = legacyPointBindingsAllowed.value
    ? productPoints.value.filter(point => form.bindings[point.metricCode]?.include) : []
  if (!included.length && !props.allowEmptyPoints) return 'validation.bindingPoints'
  if (props.allowEmptyPoints && !temperatureEnabled.value && included.length > 0 && !form.numericSourceId) return 'validation.bindingNumericSource'
  if (included.some(point => !validBinding(form.bindings[point.metricCode]))) return 'validation.bindingPoints'
  if (temperatureUsesPlan.value && requireTemperaturePlan && !planReady.value) return 'validation.temperaturePreview'
  return null
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

function emptyBinding(required: boolean, pointName: string, suffixCode: string, dataType?: string): BindingRow {
  return { include: required, mode: 'existing', existingPointId: undefined, pointCode: joinPointCode(form.pointCodePrefix, suffixCode), pointName, namingRuleId: '', familyCode: '', componentCode: '', dataType: dataType ?? 'ANALOG' }
}

function bindingMode(row: BindingRow | undefined): BindingPointMode {
  return form.mode === 'new' ? 'new' : (row?.mode ?? 'existing')
}

function nullable(value: string): string | null { return value.trim() || null }

function joinPointCode(prefix: string, suffix: string): string {
  const normalizedPrefix = prefix.trim().replace(/_+$/, '')
  const normalizedSuffix = suffix.trim().replace(/^_+/, '')
  return normalizedPrefix && normalizedSuffix ? `${normalizedPrefix}_${normalizedSuffix}` : ''
}
</script>

<template>
  <ElDialog :model-value="open" :title="t('deviceOnboarding.pending.binding')" width="min(760px, 94vw)" @update:model-value="emit('close')">
    <ElForm label-position="top" class="binding-form" @submit.prevent="submit">
      <ElAlert v-if="doorGatewayMissing" :title="t('deviceOnboarding.validation.doorGatewayRequired')" type="warning" show-icon :closable="false" />
      <div v-if="hasAutoSummary" class="auto-summary">
        <div v-if="singleProductAutoMatched" class="summary-item">
          <span>{{ t('deviceOnboarding.labels.productName') }}</span>
          <strong>{{ product?.productName }}</strong>
        </div>
        <div v-if="isDoorSensor" class="summary-item">
          <span>{{ t('deviceOnboarding.labels.bluetoothGateway') }}</span>
          <strong>{{ doorGatewayLabel }}</strong>
        </div>
        <div v-if="buildingAutoSummary" class="summary-item">
          <span>{{ t('deviceOnboarding.labels.building') }}</span>
          <strong>{{ selectedBuildingLabel }}</strong>
        </div>
        <div v-if="spaceAutoSummary" class="summary-item">
          <span>{{ t('deviceOnboarding.labels.space') }}</span>
          <strong>{{ selectedSpaceLabel }}</strong>
        </div>
        <div v-if="singleSystemAutoMatched" class="summary-item">
          <span>{{ t('deviceOnboarding.labels.systemGroup') }}</span>
          <strong>{{ selectedSystemLabel }}</strong>
        </div>
      </div>

      <div class="form-grid">
        <ElFormItem v-if="!singleProductAutoMatched" :label="t('deviceOnboarding.labels.productName')" required>
          <ElSelect v-model="form.productId" class="wide-control" :filterable="productSearchEnabled" :remote="productSearchEnabled" :remote-method="keyword => emit('product-search', keyword)" :loading="productLoading" @change="changeProduct">
            <ElOption v-for="item in products" :key="item.productId" :label="item.productName" :value="item.productId" />
          </ElSelect>
          <ElPagination v-if="productTotal > productSize" size="small" layout="total, prev, next" :current-page="productPage" :page-size="productSize" :total="productTotal" @current-change="page => emit('product-page-change', page)" />
        </ElFormItem>
        <ElFormItem v-if="!buildingAutoSummary" :label="t('deviceOnboarding.labels.building')" required>
          <ElSelect v-model="form.buildingId" class="wide-control" :disabled="Boolean(bindingOptions) || doorGatewayBound" @change="changeBuilding">
            <ElOption v-for="item in buildingOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="!batchMode && !spaceAutoSummary" :label="t('deviceOnboarding.labels.space')" required>
          <ElSelect v-model="form.spaceId" class="wide-control" :disabled="doorGatewayBound" @change="changeScope">
            <ElOption v-for="item in scopeSpaces" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="!singleSystemAutoMatched" :label="t('deviceOnboarding.labels.systemGroup')" required>
          <ElSelect v-model="form.systemGroupId" class="wide-control" @change="changeScope">
            <ElOption v-for="item in scopeSystems" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" />
          </ElSelect>
        </ElFormItem>
      </div>

      <ElAlert v-if="pending?.identityType === 'DAIKIN_UNIT' && !productLoading && productTotal === 0" :title="t('deviceOnboarding.messages.noCompatibleProduct')" type="warning" show-icon :closable="false" />

      <ElFormItem v-if="!batchMode" :label="t('deviceOnboarding.labels.targetEquipment')" required>
        <ElRadioGroup v-model="form.mode" @change="changeEquipmentMode">
          <ElRadio value="new">{{ t('deviceOnboarding.labels.newEquipment') }}</ElRadio>
          <ElRadio value="existing">{{ t('deviceOnboarding.labels.existingEquipment') }}</ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <template v-if="form.mode === 'existing'">
        <ElFormItem :label="t('deviceOnboarding.labels.existingEquipment')" required>
          <ElSelect v-model="form.existingEquipmentId" class="wide-control" @change="changeEquipment">
            <ElOption v-for="item in equipmentOptions" :key="item.equipmentId" :label="item.equipmentName" :value="item.equipmentId" />
          </ElSelect>
          <ElPagination v-if="bindingOptions && bindingOptions.equipmentTotal > bindingOptions.equipmentSize" size="small" layout="total, prev, next" :current-page="bindingOptions.equipmentPage" :page-size="bindingOptions.equipmentSize" :total="bindingOptions.equipmentTotal" @current-change="page => emit('equipment-page-change', page, form.spaceId, form.systemGroupId)" />
        </ElFormItem>
      </template>
      <template v-else>
        <div class="form-grid">
          <ElFormItem v-if="!batchMode" :label="t('deviceOnboarding.labels.equipmentName')" required>
            <ElInput v-model="form.equipmentName" maxlength="100" @input="equipmentNameTouched = true" />
          </ElFormItem>
          <ElFormItem :label="t('deviceOnboarding.labels.manufacturer')">
            <ElInput v-model="form.manufacturer" maxlength="100" />
          </ElFormItem>
        </div>
      </template>

      <section v-if="temperatureEnabled" class="temperature-binding">
        <h2>{{ t('deviceOnboarding.temperature.title') }}</h2>
        <ElRadioGroup v-model="form.temperatureMode" @change="changeTemperatureMode">
          <ElRadio value="AUTO">{{ t('deviceOnboarding.temperature.auto') }}</ElRadio>
          <ElRadio value="MANUAL">{{ t('deviceOnboarding.temperature.manual') }}</ElRadio>
          <ElRadio value="STATE_ONLY">{{ t('deviceOnboarding.temperature.stateOnly') }}</ElRadio>
        </ElRadioGroup>
        <ElFormItem v-if="temperatureManual" :label="t('deviceOnboarding.temperature.template')" required>
          <ElSelect v-model="form.temperatureTemplateProductId" class="wide-control" :loading="temperatureOptionsLoading" @change="changeTemperatureTemplate">
            <ElOption v-for="template in temperatureOptions?.templates ?? []" :key="template.productId" :label="template.productName" :value="template.productId" />
          </ElSelect>
        </ElFormItem>
        <div v-if="temperatureUsesPlan" class="temperature-preview-actions">
          <ElButton type="primary" :loading="temperaturePreviewing" @click="previewTemperature">{{ t('deviceOnboarding.actions.previewTemperature') }}</ElButton>
        </div>
        <ElAlert v-if="temperaturePreviewError" :title="temperaturePreviewError" type="error" show-icon :closable="false" />
        <div v-if="plannedRows.length" class="temperature-plan-list">
          <article v-for="plan in plannedRows" :key="plan.pendingId" class="temperature-plan">
            <div class="temperature-plan-heading"><strong>{{ plan.templateName || t('common.missing') }}</strong><ElTag :type="plan.status === 'READY' || plan.status === 'COMPLETE' ? 'success' : 'warning'">{{ t(`deviceOnboarding.temperature.planStatus.${plan.status}`) }}</ElTag></div>
            <p v-if="plan.message">{{ plan.message }}</p>
            <ul><li v-for="point in plan.points" :key="point.metricCode"><strong>{{ point.semantic }}</strong><span>{{ point.pointName || point.metricCode }}{{ ' · ' }}{{ point.unit }}{{ ' · ' }}{{ t(`deviceOnboarding.temperature.pointAction.${point.action}`) }}</span></li></ul>
          </article>
        </div>
        <section v-if="temperatureManual && form.mode === 'existing' && !batchMode && temperatureMappingPoints.length" class="temperature-reuse">
          <h3>{{ t('deviceOnboarding.temperature.reusePoints') }}</h3>
          <ElFormItem v-for="point in temperatureMappingPoints" :key="point.metricCode" :label="`${point.semantic} · ${point.unit}`">
            <ElSelect v-model="form.temperatureExistingPointIds[point.metricCode]" clearable class="wide-control" @change="changeTemperatureExistingPoint">
              <ElOption v-for="candidate in selectedPoints" :key="candidate.pointId" :label="`${candidate.pointName} · ${candidate.pointCode}`" :value="candidate.pointId" />
            </ElSelect>
          </ElFormItem>
        </section>
      </section>

      <section v-if="batchMode" class="batch-preview">
        <h2>{{ t('deviceOnboarding.pending.batchPreview') }}</h2>
        <ElTable :data="batchPreview" row-key="pendingId" max-height="320">
          <ElTableColumn :label="t('deviceOnboarding.labels.roomCode')" prop="roomCode" min-width="100" />
          <ElTableColumn :label="t('deviceOnboarding.labels.monitorAddress')" prop="monitorAddress" min-width="110" />
          <ElTableColumn :label="t('deviceOnboarding.labels.assetReferenceCode')" prop="assetReferenceCode" min-width="150" />
          <ElTableColumn :label="t('deviceOnboarding.labels.equipmentName')" prop="equipmentName" min-width="180" />
        </ElTable>
      </section>

      <ElFormItem v-if="requiresNumericSource" :label="t('deviceOnboarding.labels.numericSource')" :required="temperatureManual || (!temperatureEnabled && hasIncludedPoints)">
        <ElSelect v-model="form.numericSourceId" class="wide-control" :loading="temperatureEnabled ? temperatureOptionsLoading : numericSourcesLoading" clearable @change="changeTemperatureSource">
          <ElOption v-for="item in numericSourceChoices" :key="item.sourceId" :label="item.label" :value="item.sourceId" />
        </ElSelect>
      </ElFormItem>

      <ElSkeleton v-if="productLoading || (!bindingOptions && assets.scopeLoading.value)" animated :rows="4" />
      <ElAlert v-else-if="!bindingOptions && assets.buildingsError.value" :title="assets.buildingsError.value.message" type="error" show-icon :closable="false" />
      <ElAlert v-else-if="!bindingOptions && assets.scopeError.value" :title="assets.scopeError.value.message" type="error" show-icon :closable="false" />
      <ElAlert v-else-if="!bindingOptions && assets.equipmentError.value" :title="assets.equipmentError.value.message" type="error" show-icon :closable="false" />
      <section v-else-if="automaticPointCreation" class="automatic-points">
        <div class="automatic-points-bar">
          <strong>{{ `${t('deviceOnboarding.labels.automaticPointCreation')} · ${productPoints.length}` }}</strong>
          <div class="automatic-points-actions">
            <ElButton link type="primary" @click="pointsExpanded = !pointsExpanded">{{ t(pointsExpanded ? 'deviceOnboarding.actions.collapsePoints' : 'deviceOnboarding.actions.expandPoints') }}</ElButton>
            <ElButton link @click="manualPointOverride = true">{{ t('deviceOnboarding.actions.switchManualPoints') }}</ElButton>
          </div>
        </div>
        <ul v-if="pointsExpanded">
          <li v-for="point in productPoints" :key="point.metricCode">
            <strong>{{ point.pointNameTemplate }}</strong>
            <span>{{ dataTypeText(point.dataType) }}</span>
            <span>{{ point.unit }}</span>
          </li>
        </ul>
      </section>
      <section v-else-if="legacyPointBindingsAllowed && productPoints.length" class="binding-points">
        <div class="automatic-points-bar">
          <h2>{{ t('deviceOnboarding.labels.pointBinding') }}</h2>
          <ElButton v-if="canAutoCreatePoints" link type="primary" @click="manualPointOverride = false">{{ t('deviceOnboarding.actions.switchAutoPoints') }}</ElButton>
        </div>
        <ElFormItem :label="t('deviceOnboarding.labels.pointCodePrefix')"><ElInput v-model="form.pointCodePrefix" maxlength="60" :placeholder="t('deviceOnboarding.messages.pointCodePrefixHint')" @change="applyPointCodePrefix" /></ElFormItem>
        <div v-for="point in productPoints" :key="point.metricCode" class="binding-row">
          <div class="point-summary">
            <ElCheckbox v-model="form.bindings[point.metricCode].include" :disabled="point.required">{{ t('deviceOnboarding.labels.includePoint') }}</ElCheckbox>
            <strong>{{ point.pointNameTemplate }}</strong>
            <span>{{ `${dataTypeText(point.dataType)} · ${point.unit}` }}</span>
          </div>
          <div v-if="form.bindings[point.metricCode].include" class="binding-fields">
            <ElFormItem v-if="form.mode === 'existing'" :label="t('deviceOnboarding.labels.bindingPointMode')">
              <ElRadioGroup v-model="form.bindings[point.metricCode].mode">
                <ElRadio value="existing">{{ t('deviceOnboarding.labels.existingPoint') }}</ElRadio>
                <ElRadio value="new">{{ t('deviceOnboarding.labels.newPoint') }}</ElRadio>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem v-if="bindingMode(form.bindings[point.metricCode]) === 'existing'" :label="t('deviceOnboarding.labels.existingPoint')">
              <ElSelect v-model="form.bindings[point.metricCode].existingPointId" clearable class="wide-control">
                <ElOption v-for="candidate in selectedPoints" :key="candidate.pointId" :label="candidate.pointName" :value="candidate.pointId" />
              </ElSelect>
            </ElFormItem>
            <template v-else>
              <ElFormItem :label="t('deviceOnboarding.labels.pointCode')"><ElInput v-model="form.bindings[point.metricCode].pointCode" maxlength="100" /></ElFormItem>
              <ElFormItem :label="t('deviceOnboarding.labels.pointName')"><ElInput v-model="form.bindings[point.metricCode].pointName" maxlength="100" /></ElFormItem>
              <ElFormItem :label="t('deviceOnboarding.labels.namingRule')">
                <ElSelect v-model="form.bindings[point.metricCode].namingRuleId" class="wide-control" :loading="namingRulesLoading" @change="ruleId => changeNamingRule(point.metricCode, ruleId)">
                  <ElOption v-for="rule in namingRules" :key="rule.ruleId" :value="rule.ruleId" :label="`${rule.ruleName} · ${rule.familyCode}/${rule.componentCode} · ${rule.pattern}`" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem :label="t('deviceOnboarding.labels.family')"><ElInput v-model="form.bindings[point.metricCode].familyCode" maxlength="20" /></ElFormItem>
              <ElFormItem :label="t('deviceOnboarding.labels.component')"><ElInput v-model="form.bindings[point.metricCode].componentCode" maxlength="20" /></ElFormItem>
              <ElFormItem :label="t('deviceOnboarding.labels.dataType')">
                <ElSelect v-model="form.bindings[point.metricCode].dataType" class="wide-control">
                  <ElOption value="ANALOG" :label="t('deviceOnboarding.dataType.analog')" />
                  <ElOption value="ACCUMULATE" :label="t('deviceOnboarding.dataType.accumulate')" />
                  <ElOption value="DIGITAL" :label="t('deviceOnboarding.dataType.digital')" />
                </ElSelect>
              </ElFormItem>
            </template>
          </div>
        </div>
      </section>
      <ElAlert v-if="submitError" :title="submitError" type="error" show-icon :closable="false" />
      <ElAlert v-if="validationKey" :title="t(`deviceOnboarding.${validationKey}`)" type="error" show-icon :closable="false" />
    </ElForm>
    <template #footer>
      <div class="dialog-footer">
        <ElCheckbox v-model="form.activateOnApprove">{{ t('deviceOnboarding.labels.activateOnApprove') }}</ElCheckbox>
        <div class="footer-actions">
          <ElButton @click="emit('close')">{{ t('deviceOnboarding.actions.cancel') }}</ElButton>
          <ElButton type="primary" :disabled="doorGatewayMissing" :loading="submitting" @click="submit">{{ t(form.activateOnApprove ? 'deviceOnboarding.actions.submitBindingAndActivate' : 'deviceOnboarding.actions.submitBinding') }}</ElButton>
        </div>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped>
.binding-form, .binding-points, .automatic-points, .temperature-binding, .temperature-plan-list, .temperature-reuse { display: grid; gap: var(--bec-space-group); }
.auto-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-navigation-width) * 0.7), 1fr)); gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); }
.summary-item { display: grid; gap: var(--bec-ref-space-4); }
.summary-item span { font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }
.summary-item strong { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.automatic-points { padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); }
.automatic-points-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
.automatic-points-actions { display: flex; align-items: center; gap: var(--bec-space-tight); }
.automatic-points ul { margin: 0; padding-left: var(--bec-space-group); display: grid; gap: var(--bec-space-tight); }
.automatic-points li { display: flex; gap: var(--bec-space-tight); flex-wrap: wrap; }
.automatic-points span { color: var(--bec-color-text-secondary); }
.form-grid, .binding-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.binding-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: var(--bec-space-group); padding-top: var(--bec-space-group); border-top: var(--bec-border-width) solid var(--bec-color-divider); }
.point-summary { display: grid; gap: var(--bec-space-tight); align-content: start; }
.point-summary span { color: var(--bec-color-text-secondary); }
.temperature-binding, .temperature-reuse { padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-card); }
.temperature-binding > div > p, .temperature-reuse p, .temperature-plan p, .temperature-plan ul { margin: 0; color: var(--bec-color-text-secondary); }
.temperature-preview-actions, .temperature-plan-heading, .dialog-footer, .footer-actions { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.dialog-footer { justify-content: space-between; width: 100%; }
.temperature-plan { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-control); }
.temperature-plan-heading { justify-content: space-between; }
.temperature-plan li { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.temperature-plan li span { color: var(--bec-color-text-secondary); }
.temperature-reuse h3 { margin: 0; font-size: var(--bec-font-size-title); }
h2 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.wide-control { width: 100%; }
@media (max-width: 640px) {
  .form-grid, .binding-fields, .binding-row { grid-template-columns: 1fr; }
}
</style>
