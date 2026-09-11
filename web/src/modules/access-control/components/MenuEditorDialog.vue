<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch } from '@/shared/ui'
import { t } from '@/locales'
import type { MenuCommand, MenuNode, MenuType } from '../models/access-control'
import type { MenuParentOption } from '../composables/use-menu-management'

const props = withDefaults(defineProps<{
  modelValue: boolean
  menu?: MenuNode | null
  initialParentId?: number
  parentOptions: MenuParentOption[]
  submitting?: boolean
}>(), { menu: null, initialParentId: 0, submitting: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; submit: [value: MenuCommand] }>()
const form = reactive({
  parentId: 0,
  menuName: '',
  menuType: 'C' as MenuType,
  path: '',
  component: '',
  perms: '',
  icon: '',
  visible: true,
  status: true,
  sortOrder: 0,
})
const typeOptions = computed(() => [
  { label: t('accessControl.menu.typeDirectory'), value: 'M' },
  { label: t('accessControl.menu.typePage'), value: 'C' },
  { label: t('accessControl.menu.typePermission'), value: 'F' },
])

watch(() => [props.modelValue, props.menu, props.initialParentId] as const, () => {
  Object.assign(form, {
    parentId: props.menu?.parentId ?? props.initialParentId,
    menuName: props.menu?.menuName ?? '',
    menuType: props.menu?.menuType ?? 'C',
    path: props.menu?.path ?? '',
    component: props.menu?.component ?? '',
    perms: props.menu?.perms ?? '',
    icon: props.menu?.icon ?? '',
    visible: (props.menu?.visible ?? 1) === 1,
    status: (props.menu?.status ?? 1) === 1,
    sortOrder: props.menu?.sortOrder ?? 0,
  })
}, { immediate: true })

function submit() {
  if (!form.menuName.trim()) return
  emit('submit', {
    parentId: form.parentId,
    menuName: form.menuName.trim(),
    menuType: form.menuType,
    path: form.path.trim() || null,
    component: form.component.trim() || null,
    perms: form.perms.trim() || null,
    icon: form.icon.trim() || null,
    visible: form.visible ? 1 : 0,
    status: form.status ? 1 : 0,
    sortOrder: form.sortOrder,
  })
}
</script>

<template>
  <ElDialog :model-value="modelValue" :title="t(menu ? 'accessControl.menu.editTitle' : 'accessControl.menu.createTitle')" :width="'var(--bec-dialog-width)'" @update:model-value="emit('update:modelValue', $event)">
    <ElForm label-position="top" @submit.prevent="submit">
      <ElFormItem :label="t('accessControl.menu.parent')"><ElSelect v-model="form.parentId"><ElOption v-for="item in parentOptions" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem>
      <ElFormItem :label="t('accessControl.menu.name')" required><ElInput v-model="form.menuName" :placeholder="t('accessControl.menu.namePlaceholder')" /></ElFormItem>
      <div class="form-grid">
        <ElFormItem :label="t('accessControl.menu.type')"><ElSelect v-model="form.menuType"><ElOption v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('accessControl.menu.order')"><ElInputNumber v-model="form.sortOrder" :min="0" /></ElFormItem>
      </div>
      <div class="form-grid">
        <ElFormItem :label="t('accessControl.menu.visible')"><ElSwitch v-model="form.visible" /></ElFormItem>
        <ElFormItem :label="t('accessControl.menu.enabled')"><ElSwitch v-model="form.status" /></ElFormItem>
      </div>
      <details>
        <summary>{{ t('accessControl.menu.advanced') }}</summary>
        <div class="advanced-grid">
          <ElFormItem :label="t('accessControl.menu.path')"><ElInput v-model="form.path" :placeholder="t('accessControl.menu.pathPlaceholder')" /></ElFormItem>
          <ElFormItem :label="t('accessControl.menu.component')"><ElInput v-model="form.component" :placeholder="t('accessControl.menu.componentPlaceholder')" /></ElFormItem>
          <ElFormItem :label="t('accessControl.menu.permission')"><ElInput v-model="form.perms" :placeholder="t('accessControl.menu.permissionPlaceholder')" /></ElFormItem>
          <ElFormItem :label="t('accessControl.menu.icon')"><ElInput v-model="form.icon" :placeholder="t('accessControl.menu.iconPlaceholder')" /></ElFormItem>
        </div>
      </details>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">{{ t('accessControl.action.cancel') }}</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">{{ t('accessControl.action.submitRequest') }}</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.form-grid, .advanced-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--bec-navigation-width)), 1fr)); gap: var(--bec-space-group); }
details { display: grid; gap: var(--bec-space-group); padding: var(--bec-space-group) 0; border-top: var(--bec-border-width) solid var(--bec-color-divider); }
summary { color: var(--bec-color-text-primary); cursor: pointer; }
</style>
