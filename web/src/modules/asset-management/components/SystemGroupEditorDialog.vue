<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetSystemGroup, AssetSystemGroupForm } from '../models/assets'

const props = withDefaults(defineProps<{ open: boolean; buildingId: string; systemGroup?: AssetSystemGroup | null; submitting?: boolean }>(), {
  systemGroup: null,
  submitting: false,
})
const emit = defineEmits<{ close: []; save: [value: AssetSystemGroupForm] }>()
const form = reactive({ systemCode: '', systemName: '', systemType: '' })
const submitted = ref(false)
const title = computed(() => t(props.systemGroup ? 'assetManagement.forms.editSystem' : 'assetManagement.forms.createSystem'))

watch(() => [props.open, props.systemGroup] as const, ([open]) => {
  if (!open) return
  submitted.value = false
  Object.assign(form, {
    systemCode: props.systemGroup?.systemCode ?? '',
    systemName: props.systemGroup?.systemName ?? '',
    systemType: props.systemGroup?.systemType ?? '',
  })
}, { immediate: true })

function submit() {
  submitted.value = true
  if (!form.systemName.trim() || (!props.systemGroup && !form.systemCode.trim())) return
  emit('save', {
    buildingId: props.buildingId,
    systemCode: nullable(form.systemCode),
    systemName: form.systemName.trim(),
    systemType: nullable(form.systemType),
    status: 'ACTIVE',
  })
}

function nullable(value: string): string | null { return value.trim() || null }
</script>

<template>
  <ElDialog :model-value="open" :title="title" @update:model-value="emit('close')">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.systemName')" required :error="submitted && !form.systemName.trim() ? t('assetManagement.forms.systemNameRequired') : undefined"><ElInput v-model="form.systemName" maxlength="100" /></ElFormItem>
      <ElFormItem :label="t('assetManagement.labels.systemCode')" :required="!systemGroup" :error="submitted && !systemGroup && !form.systemCode.trim() ? t('assetManagement.forms.systemCodeRequired') : undefined"><ElInput v-model="form.systemCode" :disabled="Boolean(systemGroup)" maxlength="50" /></ElFormItem>
      <ElFormItem :label="t('assetManagement.labels.systemType')"><ElInput v-model="form.systemType" maxlength="30" /></ElFormItem>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton></template>
  </ElDialog>
</template>
