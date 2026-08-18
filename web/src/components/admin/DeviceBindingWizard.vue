<template>
  <a-drawer :open="open" title="设备接入向导" width="min(980px, 97vw)" destroy-on-close @close="$emit('close')">
    <a-steps :current="current" :items="steps" size="small" class="binding-steps" />
    <a-alert v-if="displayError" class="binding-error" type="error" show-icon :message="displayError" />

    <template v-if="bindResult">
      <a-result :status="activationResult?.status === 'ACTIVE' ? 'success' : 'info'" :title="activationResult?.status === 'ACTIVE' ? '设备身份已启用' : '设备已绑定，身份仍处于停用状态'" :sub-title="resultSubtitle">
        <template #extra>
          <a-space wrap>
            <a-button @click="$emit('close')">关闭</a-button>
            <a-button v-if="!activationResult" type="primary" danger :loading="activating" @click="$emit('activate', bindResult.identityId)">确认启用身份</a-button>
          </a-space>
        </template>
      </a-result>
      <a-descriptions bordered size="small" :column="2" class="binding-result-detail">
        <a-descriptions-item label="身份 ID">{{ bindResult.identityId }}</a-descriptions-item>
        <a-descriptions-item label="设备 ID">{{ bindResult.equipmentId }}</a-descriptions-item>
        <a-descriptions-item label="已创建/映射测点">{{ bindResult.pointIds.length }}</a-descriptions-item>
        <a-descriptions-item label="配置生效">{{ (activationResult ?? bindResult).configEffective ? '已生效' : '未确认生效' }}</a-descriptions-item>
      </a-descriptions>
    </template>

    <template v-else>
      <section v-if="current === 0" class="binding-stage">
        <div class="binding-stage-heading"><h3>选择启用的产品模板</h3><p>产品定义期望协议、身份类型和必须完成的测点映射。</p></div>
        <a-select v-model:value="form.productId" show-search option-filter-prop="label" placeholder="选择产品" style="width:100%" :options="productOptions" @change="changeProduct" />
        <a-skeleton v-if="productLoading" active :paragraph="{ rows: 4 }" />
        <a-descriptions v-else-if="product" bordered size="small" :column="2" class="binding-selection-detail">
          <a-descriptions-item label="产品">{{ product.productName }}（{{ product.productCode }}）</a-descriptions-item>
          <a-descriptions-item label="设备类型">{{ product.equipmentTypeCode }}</a-descriptions-item>
          <a-descriptions-item label="Profile">{{ product.expectedProfileCode }}</a-descriptions-item>
          <a-descriptions-item label="启用测点">{{ enabledTemplates.length }}</a-descriptions-item>
        </a-descriptions>
      </section>

      <section v-else-if="current === 1" class="binding-stage">
        <div class="binding-stage-heading"><h3>确定设备归属</h3><p>建筑、空间和系统分组必须属于同一条经后端校验的资产链。</p></div>
        <div class="binding-scope-grid">
          <a-select v-model:value="form.buildingId" show-search option-filter-prop="label" placeholder="建筑 *" :options="buildings" @change="changeBuilding" />
          <a-select v-model:value="form.spaceId" show-search option-filter-prop="label" placeholder="空间 *" :options="spaces" :disabled="!form.buildingId" />
          <a-select v-model:value="form.systemGroupId" show-search option-filter-prop="label" placeholder="系统分组 *" :options="groups" :disabled="!form.buildingId" />
        </div>
        <a-radio-group v-model:value="form.mode" class="binding-mode" @change="changeMode">
          <a-radio-button value="existing">绑定到现有设备</a-radio-button>
          <a-radio-button value="new">新建设备台账</a-radio-button>
        </a-radio-group>
        <div v-if="form.mode === 'existing'" class="binding-target-form">
          <a-select v-model:value="form.existingEquipmentId" show-search option-filter-prop="label" placeholder="选择现有设备 *" style="width:100%" :options="equipment" :disabled="!form.buildingId" @change="changeEquipment" />
          <p>仅显示当前建筑筛选结果；空间、系统归属仍由服务端按设备档案复核。</p>
        </div>
        <div v-else class="binding-target-form binding-new-equipment">
          <a-input v-model:value="form.equipmentName" maxlength="100" placeholder="新设备名称 *" />
          <a-input v-model:value="form.manufacturer" maxlength="100" placeholder="厂商（可选）" />
        </div>
      </section>

      <section v-else-if="current === 2" class="binding-stage">
        <div class="binding-stage-heading"><h3>映射标准指标</h3><p>{{ form.mode === 'existing' ? '每个启用模板选择一个现有测点，服务端会核对单位和后缀。' : '命名规则当前没有查询接口，必须明确填写规则 ID、族、组件、编码和数据类型，浏览器不做猜测。' }}</p></div>
        <a-empty v-if="enabledTemplates.length === 0" description="所选产品没有启用的测点模板" />
        <div v-for="template in enabledTemplates" :key="template.metricCode" class="binding-point-map">
          <div class="binding-template-summary"><strong>{{ template.pointNameTemplate }}</strong><span>{{ template.metricCode }} · {{ template.suffixCode }} · {{ template.unit }}</span><a-tag v-if="template.required" color="blue">必需</a-tag></div>
          <a-select v-if="form.mode === 'existing'" v-model:value="mapping(template.metricCode).existingPointId" allow-clear show-search option-filter-prop="label" placeholder="选择现有测点" :options="pointOptions" style="width:100%" />
          <div v-else class="binding-new-point-grid">
            <a-input v-model:value="mapping(template.metricCode).pointCode" maxlength="100" placeholder="测点编码 *" />
            <a-input v-model:value="mapping(template.metricCode).pointName" maxlength="100" :placeholder="`测点名称（默认 ${template.pointNameTemplate}）`" />
            <a-input v-model:value="mapping(template.metricCode).namingRuleId" maxlength="32" placeholder="命名规则 ID *" />
            <a-input v-model:value="mapping(template.metricCode).familyCode" maxlength="20" placeholder="族编码 *" />
            <a-input v-model:value="mapping(template.metricCode).componentCode" maxlength="20" placeholder="组件编码 *" />
            <a-select v-model:value="mapping(template.metricCode).dataType" placeholder="数据类型 *" :options="dataTypeOptions" />
          </div>
        </div>
      </section>

      <section v-else class="binding-stage">
        <div class="binding-stage-heading"><h3>复核后执行绑定</h3><p>绑定会创建或关联设备、测点和停用身份；不会自动启用设备上行链路。</p></div>
        <a-alert type="warning" show-icon message="绑定完成后身份仍停用，必须检查结果并单独确认启用。" />
        <a-descriptions bordered size="small" :column="2" class="binding-review">
          <a-descriptions-item label="待绑定身份">{{ pending.identityValue }}</a-descriptions-item>
          <a-descriptions-item label="Profile">{{ pending.profileCode }} v{{ pending.lastProfileVersion }}</a-descriptions-item>
          <a-descriptions-item label="产品">{{ product?.productName }}</a-descriptions-item>
          <a-descriptions-item label="目标方式">{{ form.mode === 'existing' ? '现有设备' : '新建设备' }}</a-descriptions-item>
          <a-descriptions-item label="建筑 / 空间 / 系统" :span="2">{{ selectedLabel(buildings, form.buildingId) }} / {{ selectedLabel(spaces, form.spaceId) }} / {{ selectedLabel(groups, form.systemGroupId) }}</a-descriptions-item>
          <a-descriptions-item label="测点映射">{{ enabledTemplates.length }} 项</a-descriptions-item>
          <a-descriptions-item label="绑定后身份">停用，等待人工启用</a-descriptions-item>
        </a-descriptions>
      </section>

      <div class="drawer-actions">
        <a-button @click="$emit('close')">取消</a-button>
        <a-button v-if="current > 0" @click="current -= 1">上一步</a-button>
        <a-button v-if="current < 3" type="primary" @click="next">下一步</a-button>
        <a-button v-else type="primary" danger :loading="binding" @click="submit">确认绑定</a-button>
      </div>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { AssetDataPointView } from '@/types/assets'
import type { DeviceBindRequest, DeviceBindResult, DeviceProductDetail, IdentityStatusResult, PendingDeviceDetail, PointBindingRequest } from '@/types/deviceOnboarding'

// 向导只收集产品、资产归属和测点映射；绑定与身份启用保持两个独立命令，最终状态由后端返回。
type Option = { label: string; value: string }
type Mapping = PointBindingRequest
const props = defineProps<{
  open: boolean; pending: PendingDeviceDetail; products: Option[]; product: DeviceProductDetail | null; productLoading: boolean;
  buildings: Option[]; spaces: Option[]; groups: Option[]; equipment: Option[]; points: AssetDataPointView[];
  bindResult: DeviceBindResult | null; activationResult: IdentityStatusResult | null; binding: boolean; activating: boolean; operationError?: string | null;
}>()
const emit = defineEmits<{ close: []; productChange: [productId: string]; buildingChange: [buildingId: string]; equipmentChange: [equipmentId: string]; bind: [request: DeviceBindRequest]; activate: [identityId: string] }>()
const current = ref(0)
const error = ref<string | null>(null)
const mappings = reactive<Record<string, Mapping>>({})
const form = reactive({ productId: '', buildingId: '', spaceId: '', systemGroupId: '', mode: 'existing' as 'existing' | 'new', existingEquipmentId: '', equipmentName: '', manufacturer: '' })
const steps = [{ title: '产品' }, { title: '归属' }, { title: '测点' }, { title: '复核' }]
const dataTypeOptions = ['DECIMAL', 'INTEGER', 'BOOLEAN', 'STRING'].map((value) => ({ label: value, value }))
const productOptions = computed(() => props.products)
const enabledTemplates = computed(() => props.product?.points.filter((item) => item.enabled).sort((a, b) => a.sortOrder - b.sortOrder) ?? [])
const pointOptions = computed(() => props.points.map((item) => ({ label: `${item.pointName}${item.pointCode ? `（${item.pointCode}）` : ''}`, value: item.pointId })))
const resultSubtitle = computed(() => props.activationResult
  ? `状态：${props.activationResult.status}；配置${props.activationResult.configEffective ? '已确认生效' : '未确认生效'}。`
  : `已绑定 ${props.bindResult?.pointIds.length ?? 0} 个测点；请在确认设备与 Profile 无误后启用身份。`)
const displayError = computed(() => error.value ?? props.operationError ?? null)

watch(() => [props.open, props.pending.pendingId] as const, ([open]) => { if (open) reset() }, { immediate: true })
watch(enabledTemplates, (templates) => { templates.forEach((item) => mapping(item.metricCode)) })

function reset() {
  current.value = 0; error.value = null
  Object.assign(form, { productId: '', buildingId: '', spaceId: '', systemGroupId: '', mode: 'existing', existingEquipmentId: '', equipmentName: '', manufacturer: '' })
  Object.keys(mappings).forEach((key) => delete mappings[key])
}
function mapping(metricCode: string): Mapping { return mappings[metricCode] ?? (mappings[metricCode] = { metricCode }) }
function changeProduct(productId: string) { Object.keys(mappings).forEach((key) => delete mappings[key]); emit('productChange', productId) }
function changeBuilding(buildingId: string) { form.spaceId = ''; form.systemGroupId = ''; form.existingEquipmentId = ''; emit('buildingChange', buildingId) }
function changeEquipment(equipmentId: string) { emit('equipmentChange', equipmentId) }
function changeMode() { form.existingEquipmentId = ''; form.equipmentName = ''; Object.values(mappings).forEach((item) => { item.existingPointId = undefined }) }

function next() {
  const failure = validateStage(current.value)
  if (failure) { error.value = failure; return }
  error.value = null; current.value += 1
}
function submit() {
  const failure = validateStage(2)
  if (failure) { error.value = failure; current.value = 2; return }
  error.value = null
  const selectedTemplates = form.mode === 'existing'
    ? enabledTemplates.value.filter((template) => template.required || Boolean(mapping(template.metricCode).existingPointId))
    : enabledTemplates.value
  const pointBindings = selectedTemplates.map((template) => {
    const source = mapping(template.metricCode)
    return form.mode === 'existing'
      ? { metricCode: template.metricCode, existingPointId: source.existingPointId }
      : { metricCode: template.metricCode, pointCode: clean(source.pointCode), pointName: clean(source.pointName) || template.pointNameTemplate, namingRuleId: clean(source.namingRuleId), familyCode: clean(source.familyCode), componentCode: clean(source.componentCode), dataType: source.dataType }
  })
  emit('bind', { productId: form.productId, buildingId: form.buildingId, spaceId: form.spaceId, systemGroupId: form.systemGroupId, existingEquipmentId: form.mode === 'existing' ? form.existingEquipmentId : null, newEquipment: form.mode === 'new' ? { equipmentName: form.equipmentName.trim(), manufacturer: form.manufacturer.trim() || null } : null, pointBindings })
}
function validateStage(stage: number) {
  if (stage === 0 && (!form.productId || !props.product)) return '请选择并加载一个启用的产品模板。'
  if (stage === 1) {
    if (!form.buildingId || !form.spaceId || !form.systemGroupId) return '请选择建筑、空间和系统分组。'
    if (form.mode === 'existing' && !form.existingEquipmentId) return '请选择要绑定的现有设备。'
    if (form.mode === 'new' && !form.equipmentName.trim()) return '请填写新设备名称。'
  }
  if (stage === 2) {
    if (enabledTemplates.value.length === 0) return '产品没有可用于绑定的启用测点。'
    if (form.mode === 'existing' && enabledTemplates.value.some((item) => item.required && !mapping(item.metricCode).existingPointId)) return '请完成所有必需测点的现有测点映射。'
    if (form.mode === 'new' && enabledTemplates.value.some((item) => { const value = mapping(item.metricCode); return !clean(value.pointCode) || !clean(value.namingRuleId) || !clean(value.familyCode) || !clean(value.componentCode) || !value.dataType })) return '新建设备时，请补全每个测点的编码、命名规则、族、组件和数据类型。'
  }
  return null
}
function clean(value?: string | null) { return value?.trim() || undefined }
function selectedLabel(options: Option[], value: string) { return options.find((item) => item.value === value)?.label ?? value }
</script>

<style scoped>
.binding-steps { margin-bottom: 26px; }.binding-error { margin-bottom: 18px; }
.binding-stage { min-height: 360px; }.binding-stage-heading { margin-bottom: 18px; }.binding-stage-heading h3 { margin: 0; font-size: 18px; }.binding-stage-heading p { max-width: 72ch; margin: 6px 0 0; color: #91a8bd; font-size: 12px; line-height: 1.7; }
.binding-selection-detail, .binding-review { margin-top: 20px; }.binding-scope-grid, .binding-new-equipment { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.binding-mode { margin: 22px 0 14px; }.binding-target-form p { color: #819aae; font-size: 12px; }.binding-new-equipment { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.binding-point-map { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 16px; padding: 14px 0; border-bottom: 1px solid rgba(120, 169, 207, .12); }.binding-template-summary { display: flex; align-items: flex-start; flex-direction: column; gap: 4px; }.binding-template-summary span { color: #819aae; font-size: 11px; }
.binding-new-point-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }.binding-result-detail { max-width: 720px; margin: 0 auto; }
@media (max-width: 760px) { .binding-scope-grid, .binding-new-equipment, .binding-new-point-grid { grid-template-columns: 1fr; }.binding-point-map { grid-template-columns: 1fr; } }
</style>
