<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElButton, ElCheckbox, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetPoint, AssetPointUpdate } from '../models/assets'

const props = withDefaults(defineProps<{ open: boolean; point?: AssetPoint | null; submitting?: boolean }>(), { point: null, submitting: false })
const emit = defineEmits<{ close: []; save: [value: AssetPointUpdate] }>()
const form = reactive({ pointName: '', minValue: undefined as number | undefined, maxValue: undefined as number | undefined, forCalculation: false })
const title = computed(() => t('assetManagement.forms.editPoint'))

watch(() => [props.open, props.point] as const, ([open]) => {
  if (!open) return
  Object.assign(form, {
    pointName: props.point?.pointName ?? '',
    minValue: props.point?.minValue ?? undefined,
    maxValue: props.point?.maxValue ?? undefined,
    forCalculation: props.point?.forCalculation ?? false,
  })
}, { immediate: true })

function submit() {
  if (!form.pointName.trim()) return
  if (form.minValue !== undefined && form.maxValue !== undefined && form.minValue > form.maxValue) return
  emit('save', {
    pointName: form.pointName.trim(),
    minValue: form.minValue ?? null,
    maxValue: form.maxValue ?? null,
    forCalculation: form.forCalculation,
  })
}
</script>

<template>
  <ElDialog :model-value="open" :title="title" @update:model-value="emit('close')">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.pointName')" required><ElInput v-model="form.pointName" maxlength="100" /></ElFormItem>
      <div class="two-columns">
        <ElFormItem :label="t('assetManagement.labels.minValue')"><ElInputNumber v-model="form.minValue" class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.maxValue')"><ElInputNumber v-model="form.maxValue" class="wide-control" /></ElFormItem>
      </div>
      <ElCheckbox v-model="form.forCalculation">{{ t('assetManagement.labels.calculation') }}</ElCheckbox>
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
