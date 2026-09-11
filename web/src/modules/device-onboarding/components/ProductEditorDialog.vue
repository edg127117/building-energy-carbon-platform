<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber } from '@/shared/ui'
import { t } from '@/locales'
import type { DeviceProductDetail, DeviceProductForm, ProductPointTemplate } from '../models/onboarding'

const props = withDefaults(defineProps<{ open: boolean; product?: DeviceProductDetail | null; submitting?: boolean }>(), { product: null, submitting: false })
const emit = defineEmits<{ close: []; save: [value: DeviceProductForm] }>()
const validationKey = ref<string | null>(null)
const form = reactive<Omit<DeviceProductForm, 'points'> & { points: ProductPointTemplate[] }>({
  productCode: '', productName: '', manufacturer: '', model: '', equipmentTypeCode: '', expectedProfileCode: '', identityType: '', points: [],
})
const title = computed(() => t(props.product ? 'deviceOnboarding.forms.editProduct' : 'deviceOnboarding.forms.createProduct'))

watch(() => [props.open, props.product] as const, ([open]) => {
  if (!open) return
  Object.assign(form, {
    productCode: props.product?.productCode ?? '',
    productName: props.product?.productName ?? '',
    manufacturer: props.product?.manufacturer ?? '',
    model: props.product?.model ?? '',
    equipmentTypeCode: props.product?.equipmentTypeCode ?? '',
    expectedProfileCode: props.product?.expectedProfileCode ?? '',
    identityType: props.product?.identityType ?? '',
    points: props.product?.points.map(point => ({ ...point })) ?? [emptyPoint(1)],
  })
  validationKey.value = null
}, { immediate: true })

function addPoint() { form.points.push(emptyPoint(form.points.length + 1)) }
function removePoint(index: number) { form.points.splice(index, 1) }

function submit() {
  validationKey.value = validate()
  if (validationKey.value) return
  emit('save', {
    productCode: form.productCode.trim(),
    productName: form.productName.trim(),
    manufacturer: nullable(form.manufacturer),
    model: nullable(form.model),
    equipmentTypeCode: form.equipmentTypeCode.trim(),
    expectedProfileCode: form.expectedProfileCode.trim(),
    identityType: form.identityType.trim(),
    points: form.points.map(point => ({
      metricCode: point.metricCode.trim(),
      pointNameTemplate: point.pointNameTemplate.trim(),
      suffixCode: point.suffixCode.trim(),
      unit: point.unit.trim(),
      minValue: point.minValue,
      maxValue: point.maxValue,
      forCalc: point.forCalc,
      required: point.required,
      sortOrder: point.sortOrder,
      enabled: point.enabled,
    })),
  })
}

function validate(): string | null {
  if (!props.product && !form.productCode.trim()) return 'validation.productCode'
  if (![form.productName, form.equipmentTypeCode, form.expectedProfileCode, form.identityType].every(value => value.trim())) return 'validation.productFields'
  if (!form.points.length) return 'validation.productPoints'
  if (form.points.some(point => !point.metricCode.trim() || !point.pointNameTemplate.trim() || !point.suffixCode.trim() || !point.unit.trim())) return 'validation.pointFields'
  if (new Set(form.points.map(point => point.metricCode.trim())).size !== form.points.length) return 'validation.duplicateMetric'
  if (form.points.some(point => point.minValue !== null && point.maxValue !== null && point.minValue > point.maxValue)) return 'validation.pointRange'
  return null
}

function emptyPoint(order: number): ProductPointTemplate {
  return { metricCode: '', pointNameTemplate: '', suffixCode: '', unit: '', minValue: null, maxValue: null, forCalc: false, required: true, sortOrder: order, enabled: true }
}

function nullable(value: string): string | null { return value.trim() || null }
</script>

<template>
  <ElDialog :model-value="open" :title="title" width="70%" @update:model-value="emit('close')">
    <ElAlert :title="t('deviceOnboarding.forms.productDraftOnly')" type="info" :closable="false" />
    <ElForm label-position="top" class="editor-form" @submit.prevent="submit">
      <div class="form-grid">
        <ElFormItem :label="t('deviceOnboarding.labels.productCode')" required><ElInput v-model="form.productCode" :disabled="Boolean(product)" maxlength="50" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.productName')" required><ElInput v-model="form.productName" maxlength="100" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.manufacturer')"><ElInput v-model="form.manufacturer" maxlength="100" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.model')"><ElInput v-model="form.model" maxlength="100" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.equipmentType')" required><ElInput v-model="form.equipmentTypeCode" maxlength="20" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.expectedProfile')" required><ElInput v-model="form.expectedProfileCode" maxlength="50" /></ElFormItem>
        <ElFormItem :label="t('deviceOnboarding.labels.identityType')" required><ElInput v-model="form.identityType" maxlength="20" /></ElFormItem>
      </div>

      <section class="points-section">
        <div class="section-heading"><h2>{{ t('deviceOnboarding.products.points') }}</h2><ElButton @click="addPoint">{{ t('deviceOnboarding.actions.addPoint') }}</ElButton></div>
        <ElEmpty v-if="!form.points.length" :description="t('deviceOnboarding.empty.points')" />
        <div v-for="(point, index) in form.points" :key="index" class="point-editor">
          <div class="point-index">{{ index + 1 }}</div><div class="point-fields">
            <ElFormItem :label="t('deviceOnboarding.labels.metricCode')" required><ElInput v-model="point.metricCode" maxlength="100" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.pointNameTemplate')" required><ElInput v-model="point.pointNameTemplate" maxlength="100" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.suffixCode')" required><ElInput v-model="point.suffixCode" maxlength="20" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.unit')" required><ElInput v-model="point.unit" maxlength="20" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.minValue')"><ElInputNumber v-model="point.minValue" class="wide-control" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.maxValue')"><ElInputNumber v-model="point.maxValue" class="wide-control" /></ElFormItem>
            <ElFormItem :label="t('deviceOnboarding.labels.sortOrder')" required><ElInputNumber v-model="point.sortOrder" :min="0" class="wide-control" /></ElFormItem>
            <div class="point-flags"><ElCheckbox v-model="point.forCalc">{{ t('deviceOnboarding.labels.calculation') }}</ElCheckbox><ElCheckbox v-model="point.required">{{ t('deviceOnboarding.labels.required') }}</ElCheckbox><ElCheckbox v-model="point.enabled">{{ t('deviceOnboarding.labels.enabled') }}</ElCheckbox></div>
          </div><ElButton link type="danger" @click="removePoint(index)">{{ t('deviceOnboarding.actions.removePoint') }}</ElButton>
        </div>
      </section>
      <ElAlert v-if="validationKey" :title="t(`deviceOnboarding.${validationKey}`)" type="error" show-icon :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('deviceOnboarding.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('deviceOnboarding.actions.save') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.editor-form, .points-section { display: grid; gap: var(--bec-space-section); margin-top: var(--bec-space-section); }
.form-grid, .point-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.section-heading, .point-editor { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); }
h2 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.point-editor { border-top: var(--bec-border-width) solid var(--bec-color-divider); padding-top: var(--bec-space-group); }
.point-editor + .point-editor { margin-top: var(--bec-space-group); }
.point-index { display: grid; place-items: center; min-width: var(--bec-control-height); min-height: var(--bec-control-height); color: var(--bec-color-text-secondary); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-control); }
.point-fields { flex: 1; }
.point-flags { display: flex; align-items: center; gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
