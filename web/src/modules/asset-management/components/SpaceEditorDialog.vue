<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect } from '@/shared/ui'
import { t } from '@/locales'
import { flattenSpaces, type AssetSpace, type AssetSpaceForm } from '../models/assets'

const props = withDefaults(defineProps<{
  open: boolean
  buildingId: string
  spaces: AssetSpace[]
  space?: AssetSpace | null
  submitting?: boolean
}>(), { space: null, submitting: false })
const emit = defineEmits<{ close: []; save: [value: AssetSpaceForm] }>()

const form = reactive({ parentSpaceId: undefined as string | undefined, spaceName: '', spaceCode: '', spaceType: '', sortOrder: undefined as number | undefined, usableArea: undefined as number | undefined })
const title = computed(() => t(props.space ? 'assetManagement.forms.editSpace' : 'assetManagement.forms.createSpace'))
const parentOptions = computed(() => flattenSpaces(props.spaces).filter(item => item.spaceId !== props.space?.spaceId))

watch(() => [props.open, props.space, props.buildingId] as const, ([open]) => {
  if (!open) return
  Object.assign(form, {
    parentSpaceId: props.space?.parentSpaceId ?? undefined,
    spaceName: props.space?.spaceName ?? '',
    spaceCode: props.space?.spaceCode ?? '',
    spaceType: props.space?.spaceType ?? '',
    sortOrder: props.space?.sortOrder ?? undefined,
    usableArea: props.space?.usableArea ?? undefined,
  })
}, { immediate: true })

function submit() {
  if (!form.spaceName.trim()) return
  emit('save', {
    buildingId: props.buildingId,
    parentSpaceId: form.parentSpaceId ?? null,
    spaceName: form.spaceName.trim(),
    spaceCode: nullable(form.spaceCode),
    spaceType: nullable(form.spaceType),
    sortOrder: form.sortOrder ?? null,
    usableArea: form.usableArea ?? null,
    status: 'ACTIVE',
  })
}

function nullable(value: string): string | null { return value.trim() || null }
</script>

<template>
  <ElDialog :model-value="open" :title="title" @update:model-value="emit('close')">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('assetManagement.labels.spaceName')" required><ElInput v-model="form.spaceName" maxlength="100" /></ElFormItem>
      <ElFormItem :label="t('assetManagement.labels.parentSpace')">
        <ElSelect v-model="form.parentSpaceId" clearable class="wide-control">
          <ElOption v-for="item in parentOptions" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" />
        </ElSelect>
      </ElFormItem>
      <div class="two-columns">
        <ElFormItem :label="t('assetManagement.labels.spaceCode')"><ElInput v-model="form.spaceCode" maxlength="50" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.spaceType')"><ElInput v-model="form.spaceType" maxlength="20" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.sortOrder')"><ElInputNumber v-model="form.sortOrder" :min="0" class="wide-control" /></ElFormItem>
        <ElFormItem :label="t('assetManagement.labels.usableArea')"><ElInputNumber v-model="form.usableArea" :min="0" class="wide-control" /></ElFormItem>
      </div>
      <ElAlert :title="t('assetManagement.forms.activeOnly')" type="info" :closable="false" />
    </ElForm>
    <template #footer><ElButton @click="emit('close')">{{ t('assetManagement.actions.cancel') }}</ElButton><ElButton type="primary" :loading="submitting" @click="submit">{{ t('assetManagement.actions.save') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-group); }
.wide-control { width: 100%; }
</style>
