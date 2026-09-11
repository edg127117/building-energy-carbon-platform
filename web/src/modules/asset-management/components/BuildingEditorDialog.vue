<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetBuilding, AssetBuildingForm } from '../models/assets'

const props = withDefaults(defineProps<{ open: boolean; building?: AssetBuilding | null; submitting?: boolean }>(), {
  building: null,
  submitting: false,
})
const emit = defineEmits<{ close: []; save: [value: AssetBuildingForm] }>()

const form = reactive({
  buildingName: '',
  buildingCode: '',
  buildingType: '',
  constructionYear: undefined as number | undefined,
  totalGfa: undefined as number | undefined,
  climateZone: '',
})
const title = computed(() => t(props.building ? 'assetManagement.forms.editBuilding' : 'assetManagement.forms.createBuilding'))

watch(() => [props.open, props.building] as const, ([open]) => {
  if (!open) return
  Object.assign(form, {
    buildingName: props.building?.buildingName ?? '',
    buildingCode: props.building?.buildingCode ?? '',
    buildingType: props.building?.buildingType ?? '',
    constructionYear: props.building?.constructionYear ?? undefined,
    totalGfa: props.building?.totalGfa ?? undefined,
    climateZone: props.building?.climateZone ?? '',
  })
}, { immediate: true })

function submit() {
  if (!form.buildingName.trim()) return
  emit('save', {
    buildingName: form.buildingName.trim(),
    buildingCode: nullable(form.buildingCode),
    buildingType: nullable(form.buildingType),
    constructionYear: form.constructionYear ?? null,
    totalGfa: form.totalGfa ?? null,
    climateZone: nullable(form.climateZone),
    status: 'ACTIVE',
  })
}

function nullable(value: string): string | null {
  return value.trim() || null
}
</script>

<template>
  <ElDialog :model-value="open" :title="title" @update:model-value="emit('close')">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.buildingName')" required>
        <ElInput v-model="form.buildingName" maxlength="100" />
      </ElFormItem>
      <ElFormItem :label="t('assetManagement.labels.buildingCode')"><ElInput v-model="form.buildingCode" maxlength="50" /></ElFormItem>
      <div class="two-columns">
        <ElFormItem :label="t('assetManagement.labels.buildingType')"><ElInput v-model="form.buildingType" maxlength="30" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.climateZone')"><ElInput v-model="form.climateZone" maxlength="30" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.constructionYear')"><ElInputNumber v-model="form.constructionYear" :min="1800" :max="2200" class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.totalGfa')"><ElInputNumber v-model="form.totalGfa" :min="0" class="wide-control" /></ElFormItem>
      </div>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer>
      <ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
