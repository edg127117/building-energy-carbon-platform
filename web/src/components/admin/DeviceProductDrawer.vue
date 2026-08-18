<template>
  <a-drawer :open="open" :title="product ? '编辑产品草稿' : '新建产品草稿'" width="min(960px, 96vw)" destroy-on-close @close="$emit('close')">
    <a-alert class="product-form-notice" type="info" show-icon message="产品启用后不可直接编辑；如需调整，请复制为新草稿。" />
    <a-form layout="vertical">
      <div class="product-form-grid">
        <a-form-item label="产品编码" required><a-input v-model:value="form.productCode" :disabled="Boolean(product)" maxlength="50" placeholder="例如 AHU_V1" /></a-form-item>
        <a-form-item label="产品名称" required><a-input v-model:value="form.productName" maxlength="100" placeholder="例如 组合式空调机组" /></a-form-item>
        <a-form-item label="厂商"><a-input v-model:value="form.manufacturer" maxlength="100" /></a-form-item>
        <a-form-item label="型号"><a-input v-model:value="form.model" maxlength="100" /></a-form-item>
        <a-form-item label="设备类型编码" required><a-input v-model:value="form.equipmentTypeCode" maxlength="20" placeholder="AHU" /></a-form-item>
        <a-form-item label="期望协议 Profile" required><a-input v-model:value="form.expectedProfileCode" maxlength="50" placeholder="例如 HVAC_V1" /></a-form-item>
        <a-form-item label="身份类型" required><a-input v-model:value="form.identityType" maxlength="20" placeholder="CLIENT_ID" /></a-form-item>
      </div>
    </a-form>

    <section class="product-points-section">
      <div class="product-section-heading">
        <div><h3>测点模板</h3><p>指标编码在同一产品内必须唯一；范围和单位由后端在绑定时再次校验。</p></div>
        <a-button @click="appendPoint">添加测点</a-button>
      </div>
      <a-empty v-if="form.points.length === 0" description="至少添加一个测点模板" />
      <div v-for="(point, index) in form.points" :key="point.localKey" class="product-point-row">
        <div class="product-point-index">{{ index + 1 }}</div>
        <div class="product-point-fields">
          <a-input v-model:value="point.metricCode" maxlength="100" placeholder="指标编码 *" />
          <a-input v-model:value="point.pointNameTemplate" maxlength="100" placeholder="测点名称模板 *" />
          <a-input v-model:value="point.suffixCode" maxlength="20" placeholder="后缀 *" />
          <a-input v-model:value="point.unit" maxlength="20" placeholder="单位 *" />
          <a-input-number v-model:value="point.minValue" placeholder="最小值" style="width:100%" />
          <a-input-number v-model:value="point.maxValue" placeholder="最大值" style="width:100%" />
          <a-input-number v-model:value="point.sortOrder" :min="0" placeholder="排序" style="width:100%" />
          <div class="product-point-flags">
            <a-checkbox v-model:checked="point.forCalc">参与计算</a-checkbox>
            <a-checkbox v-model:checked="point.required">必需</a-checkbox>
            <a-checkbox v-model:checked="point.enabled">启用</a-checkbox>
          </div>
        </div>
        <a-button type="text" danger @click="removePoint(index)">移除</a-button>
      </div>
    </section>

    <a-alert v-if="validationError" class="product-form-error" type="error" show-icon :message="validationError" />
    <div class="drawer-actions">
      <a-button @click="$emit('close')">取消</a-button>
      <a-button type="primary" :loading="submitting" @click="submit">保存草稿</a-button>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { DeviceProductDetail, DeviceProductForm, DeviceProductPointTemplate } from '@/types/deviceOnboarding'

// 抽屉只编辑草稿及其测点模板；产品状态、在用保护和最终字段约束仍由后端校验。
type EditablePoint = DeviceProductPointTemplate & { localKey: string }

const props = defineProps<{ open: boolean; product: DeviceProductDetail | null; submitting: boolean }>()
const emit = defineEmits<{ close: []; save: [form: DeviceProductForm] }>()
const validationError = ref<string | null>(null)
const form = reactive<Omit<DeviceProductForm, 'points'> & { points: EditablePoint[] }>({
  productCode: '', productName: '', manufacturer: '', model: '', equipmentTypeCode: '', expectedProfileCode: '', identityType: 'CLIENT_ID', points: [],
})

watch(() => [props.open, props.product] as const, ([open]) => { if (open) reset() }, { immediate: true })

function makePoint(source?: DeviceProductPointTemplate, index = 0): EditablePoint {
  return {
    localKey: source?.templatePointId ?? `new-${Date.now()}-${index}`,
    templatePointId: source?.templatePointId,
    metricCode: source?.metricCode ?? '', pointNameTemplate: source?.pointNameTemplate ?? '', suffixCode: source?.suffixCode ?? '', unit: source?.unit ?? '',
    minValue: source?.minValue ?? null, maxValue: source?.maxValue ?? null, forCalc: source?.forCalc ?? false,
    required: source?.required ?? true, sortOrder: source?.sortOrder ?? index + 1, enabled: source?.enabled ?? true,
  }
}

function reset() {
  const source = props.product
  Object.assign(form, {
    productCode: source?.productCode ?? '', productName: source?.productName ?? '', manufacturer: source?.manufacturer ?? '', model: source?.model ?? '',
    equipmentTypeCode: source?.equipmentTypeCode ?? '', expectedProfileCode: source?.expectedProfileCode ?? '', identityType: source?.identityType ?? 'CLIENT_ID',
    points: source?.points.map(makePoint) ?? [makePoint(undefined, 0)],
  })
  validationError.value = null
}

function appendPoint() { form.points.push(makePoint(undefined, form.points.length)) }
function removePoint(index: number) { form.points.splice(index, 1) }

function submit() {
  const error = validate()
  if (error) { validationError.value = error; return }
  validationError.value = null
  emit('save', {
    productCode: form.productCode?.trim(), productName: form.productName.trim(), manufacturer: form.manufacturer?.trim() || null,
    model: form.model?.trim() || null, equipmentTypeCode: form.equipmentTypeCode.trim(), expectedProfileCode: form.expectedProfileCode.trim(), identityType: form.identityType.trim(),
    points: form.points.map((item) => ({
      metricCode: item.metricCode.trim(), pointNameTemplate: item.pointNameTemplate.trim(), suffixCode: item.suffixCode.trim(), unit: item.unit.trim(),
      minValue: item.minValue, maxValue: item.maxValue, forCalc: item.forCalc, required: item.required, sortOrder: item.sortOrder, enabled: item.enabled,
    })),
  })
}

function validate() {
  if (!props.product && !form.productCode?.trim()) return '请填写产品编码。'
  if (![form.productName, form.equipmentTypeCode, form.expectedProfileCode, form.identityType].every((value) => value.trim())) return '请填写产品名称、设备类型、Profile 和身份类型。'
  if (form.points.length === 0) return '产品至少需要一个测点模板。'
  if (form.points.some((item) => !item.metricCode.trim() || !item.pointNameTemplate.trim() || !item.suffixCode.trim() || !item.unit.trim())) return '请补全每个测点的指标编码、名称模板、后缀和单位。'
  const metrics = form.points.map((item) => item.metricCode.trim())
  if (new Set(metrics).size !== metrics.length) return '同一产品内的指标编码不能重复。'
  if (form.points.some((item) => item.minValue !== null && item.maxValue !== null && item.minValue > item.maxValue)) return '测点最小值不能大于最大值。'
  return null
}
</script>

<style scoped>
.product-form-notice { margin-bottom: 18px; }
.product-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.product-points-section { margin-top: 10px; }
.product-section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.product-section-heading h3 { margin: 0; font-size: 16px; }.product-section-heading p { margin: 5px 0 0; color: #91a8bd; font-size: 12px; }
.product-point-row { display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; gap: 10px; align-items: start; padding: 14px 0; border-top: 1px solid rgba(120, 169, 207, .12); }
.product-point-index { width: 28px; height: 28px; display: grid; place-items: center; color: #82badf; background: rgba(66, 165, 255, .1); border-radius: 9px; }
.product-point-fields { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.product-point-flags { grid-column: span 4; display: flex; flex-wrap: wrap; gap: 18px; }
.product-form-error { margin-top: 16px; }
@media (max-width: 760px) { .product-form-grid, .product-point-fields { grid-template-columns: 1fr; }.product-point-flags { grid-column: auto; } }
</style>
