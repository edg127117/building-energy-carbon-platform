<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, type FormInstance, type FormRules } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetBuilding, AssetBuildingForm } from '../models/assets'

const props = withDefaults(defineProps<{ open: boolean; building?: AssetBuilding | null; submitting?: boolean }>(), {
  building: null,
  submitting: false,
})
const emit = defineEmits<{ close: []; save: [value: AssetBuildingForm] }>()

const formRef = ref<FormInstance>()
const validating = ref(false)
const busy = computed(() => validating.value || props.submitting)
let generation = 0
const form = reactive({
  buildingName: '',
  buildingCode: '',
  buildingType: '',
  constructionYear: undefined as number | undefined,
  totalGfa: undefined as number | undefined,
  climateZone: '',
  regionCode: '',
})
const title = computed(() => t(props.building ? 'assetManagement.forms.editBuilding' : 'assetManagement.forms.createBuilding'))

const requiredText = (message: string) => ({
  required: true,
  whitespace: true,
  message,
  trigger: 'blur' as const,
})
const createRules: FormRules = {
  buildingName: [requiredText(t('assetManagement.forms.buildingNameRequired'))],
  buildingType: [requiredText(t('assetManagement.forms.buildingTypeRequired'))],
  totalGfa: [{ required: true, type: 'number', min: 0, message: t('assetManagement.forms.totalGfaRequired'), trigger: 'change' }],
  climateZone: [requiredText(t('assetManagement.forms.climateZoneRequired'))],
  regionCode: [
    requiredText(t('assetManagement.forms.regionCodeRequired')),
    { pattern: /^\S+$/, message: t('assetManagement.forms.regionCodeInvalid'), trigger: 'blur' },
  ],
}
const editRules: FormRules = {
  buildingName: [requiredText(t('assetManagement.forms.buildingNameRequired'))],
}
// 更新接口允许旧建筑省略创建阶段新增的字段；只在创建时执行完整必填校验。
const rules = computed<FormRules>(() => props.building ? editRules : createRules)

watch(() => [props.open, props.building] as const, ([open]) => {
  const current = ++generation
  validating.value = false
  if (!open) return
  Object.assign(form, {
    buildingName: props.building?.buildingName ?? '',
    buildingCode: props.building?.buildingCode ?? '',
    buildingType: props.building?.buildingType ?? '',
    constructionYear: props.building?.constructionYear ?? undefined,
    totalGfa: props.building?.totalGfa ?? undefined,
    climateZone: props.building?.climateZone ?? '',
    regionCode: props.building?.regionCode ?? '',
  })
  void nextTick(() => {
    if (current === generation && !validating.value) formRef.value?.clearValidate()
  })
}, { immediate: true, flush: 'sync' })

async function submit() {
  if (busy.value || !props.open) return
  const current = generation
  validating.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    // 校验期间关闭或切换编辑对象后，不能把旧表单提交到新的操作上下文。
    if (!valid || current !== generation || !props.open || props.submitting) return
    emit('save', {
      buildingName: form.buildingName.trim(),
      buildingCode: nullable(form.buildingCode),
      buildingType: nullable(form.buildingType),
      constructionYear: form.constructionYear ?? null,
      totalGfa: form.totalGfa ?? null,
      climateZone: nullable(form.climateZone),
      regionCode: nullable(form.regionCode),
      status: 'ACTIVE',
    })
  } finally {
    if (current === generation) validating.value = false
  }
}

function close() {
  if (busy.value) return
  generation++
  emit('close')
}

function nullable(value: string): string | null {
  return value.trim() || null
}
</script>

<template>
  <ElDialog :model-value="open" :title="title" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="close">
    <ElForm ref="formRef" :model="form" :rules="rules" :disabled="busy" label-position="top" @submit.prevent="submit">
      <ElFormItem data-test="building-name" prop="buildingName" :label="t('assetManagement.labels.buildingName')" required>
        <ElInput v-model="form.buildingName" maxlength="100" />
      </ElFormItem>
      <ElFormItem :label="t('assetManagement.labels.buildingCode')"><ElInput v-model="form.buildingCode" maxlength="50" /></ElFormItem>
      <div class="two-columns">
        <ElFormItem data-test="building-type" prop="buildingType" :label="t('assetManagement.labels.buildingType')" :required="!building"><ElInput v-model="form.buildingType" maxlength="30" /></ElFormItem>
        <ElFormItem data-test="building-climate-zone" prop="climateZone" :label="t('assetManagement.labels.climateZone')" :required="!building"><ElInput v-model="form.climateZone" maxlength="30" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.constructionYear')"><ElInputNumber v-model="form.constructionYear" :min="1800" :max="2200" class="wide-control" /></ElFormItem>
        <ElFormItem data-test="building-total-gfa" prop="totalGfa" :label="t('assetManagement.labels.totalGfa')" :required="!building"><ElInputNumber v-model="form.totalGfa" :min="0" class="wide-control" /></ElFormItem>
        <ElFormItem data-test="building-region-code" prop="regionCode" :label="t('assetManagement.labels.regionCode')" :required="!building"><ElInput v-model="form.regionCode" maxlength="12" /></ElFormItem>
      </div>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer>
      <ElButton :disabled="busy" @click="close">{{ t('assetManagement.actions.cancel') }}</ElButton>
      <ElButton type="primary" :loading="busy" :disabled="busy" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
