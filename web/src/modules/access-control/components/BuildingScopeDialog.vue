<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElOption, ElSelect } from '@/shared/ui'
import { t } from '@/locales'
import type { BuildingOption, UserView } from '../models/access-control'

const props = withDefaults(defineProps<{
  modelValue: boolean
  user?: UserView | null
  buildings: BuildingOption[]
  submitting?: boolean
}>(), { user: null, submitting: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; submit: [buildingIds: string[]] }>()
const selected = ref<string[]>([])
const options = computed(() => props.buildings.map(item => ({
  label: item.buildingCode ? `${item.buildingName} · ${item.buildingCode}` : item.buildingName,
  value: item.buildingId,
})))

watch(() => [props.modelValue, props.user] as const, () => {
  selected.value = [...(props.user?.buildingIds ?? [])]
}, { immediate: true })
</script>

<template>
  <ElDialog :model-value="modelValue" :title="t('accessControl.user.buildingDialogTitle')" :width="'var(--bec-dialog-width)'" @update:model-value="emit('update:modelValue', $event)">
    <ElAlert :title="t('accessControl.user.buildingDialogNotice')" type="warning" show-icon :closable="false" />
    <ElForm label-position="top">
      <ElFormItem :label="t('accessControl.user.buildings')">
        <ElSelect v-model="selected" multiple filterable :placeholder="t('accessControl.user.buildingPlaceholder')">
          <ElOption v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">{{ t('accessControl.action.cancel') }}</ElButton>
      <ElButton type="primary" :loading="submitting" @click="emit('submit', selected)">{{ t('accessControl.action.submitRequest') }}</ElButton>
    </template>
  </ElDialog>
</template>
