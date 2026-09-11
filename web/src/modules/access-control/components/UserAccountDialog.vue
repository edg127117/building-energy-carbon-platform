<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect, type FormInstance, type FormRules } from '@/shared/ui'
import { t } from '@/locales'
import type { BuildingOption, FormalRoleKey, OpenUserAccountCommand, UserProfileUpdate, UserView } from '../models/access-control'

const props = withDefaults(defineProps<{
  modelValue: boolean
  mode: 'open' | 'edit'
  user?: UserView | null
  buildings: BuildingOption[]
  submitting?: boolean
}>(), { user: null, submitting: false })
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  openAccount: [value: OpenUserAccountCommand]
  updateProfile: [value: UserProfileUpdate]
}>()
const formRef = ref<FormInstance>()
const form = reactive({ username: '', nickname: '', phone: '', roles: [] as FormalRoleKey[], buildingIds: [] as string[] })
const roleOptions = computed(() => [
  { label: t('accessControl.role.BUILDING_OWNER'), value: 'BUILDING_OWNER' },
  { label: t('accessControl.role.ENERGY_MANAGER'), value: 'ENERGY_MANAGER' },
  { label: t('accessControl.role.THIRD_PARTY'), value: 'THIRD_PARTY' },
  { label: t('accessControl.role.PLATFORM_ADMIN'), value: 'PLATFORM_ADMIN' },
])
const buildingOptions = computed(() => props.buildings.map(item => ({
  label: item.buildingCode ? `${item.buildingName} · ${item.buildingCode}` : item.buildingName,
  value: item.buildingId,
})))
const rules = computed<FormRules>(() => ({
  username: [{ required: props.mode === 'open', message: t('accessControl.validation.username'), trigger: 'blur' }],
}))

watch(() => [props.modelValue, props.mode, props.user] as const, () => {
  form.username = props.mode === 'open' ? '' : (props.user?.username ?? '')
  form.nickname = props.user?.nickname ?? ''
  form.phone = props.user?.phone ?? ''
  form.roles = [...(props.user?.roles ?? [])]
  form.buildingIds = [...(props.user?.buildingIds ?? [])]
}, { immediate: true })

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (props.mode === 'open') {
    if (form.roles.length === 0) return
    emit('openAccount', {
      username: form.username.trim(),
      nickname: form.nickname.trim() || null,
      phone: form.phone.trim() || null,
      roleKeys: [...form.roles],
      buildingIds: [...form.buildingIds],
    })
    return
  }
  emit('updateProfile', { nickname: form.nickname.trim() || null, phone: form.phone.trim() || null })
}
</script>

<template>
  <ElDialog :model-value="modelValue" :title="t(mode === 'open' ? 'accessControl.user.openTitle' : 'accessControl.user.editTitle')" :width="'var(--bec-dialog-width)'" @update:model-value="emit('update:modelValue', $event)">
    <ElAlert v-if="mode === 'open'" :title="t('accessControl.user.openNotice')" type="info" show-icon :closable="false" />
    <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('accessControl.user.username')" prop="username"><ElInput v-model="form.username" :disabled="mode === 'edit'" :placeholder="t('accessControl.user.usernamePlaceholder')" /></ElFormItem>
      <ElFormItem :label="t('accessControl.user.nickname')"><ElInput v-model="form.nickname" :placeholder="t('accessControl.user.nicknamePlaceholder')" /></ElFormItem>
      <ElFormItem :label="t('accessControl.user.phone')"><ElInput v-model="form.phone" :placeholder="t('accessControl.user.phonePlaceholder')" /></ElFormItem>
      <template v-if="mode === 'open'">
        <ElFormItem :label="t('accessControl.user.roles')"><ElCheckboxGroup v-model="form.roles"><ElCheckbox v-for="item in roleOptions" :key="item.value" :value="item.value">{{ item.label }}</ElCheckbox></ElCheckboxGroup></ElFormItem>
        <ElFormItem :label="t('accessControl.user.buildings')"><ElSelect v-model="form.buildingIds" multiple filterable :placeholder="t('accessControl.user.buildingPlaceholder')"><ElOption v-for="item in buildingOptions" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem>
      </template>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">{{ t('accessControl.action.cancel') }}</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">{{ t(mode === 'open' ? 'accessControl.action.submitRequest' : 'accessControl.action.save') }}</ElButton>
    </template>
  </ElDialog>
</template>
