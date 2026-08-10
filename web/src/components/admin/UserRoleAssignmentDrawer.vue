<template>
  <a-drawer :open="open" title="分配固定角色" width="420" @close="$emit('close')">
    <a-alert type="info" show-icon message="保存会全量替换该用户的正式角色，后端将撤销旧登录态。" />
    <a-checkbox-group v-model:value="selected" class="assignment-list" :options="options" />
    <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" :loading="submitting" @click="$emit('save', selected)">保存角色</a-button></div>
  </a-drawer>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import type { FormalRoleKey, UserAdminView } from '@/types/admin'
/** 固定四角色的全量选择器，不提供角色新增、删除或重命名入口。 */
const props = defineProps<{ open: boolean; user?: UserAdminView | null; submitting?: boolean }>()
defineEmits<{ close: []; save: [roles: FormalRoleKey[]] }>()
const selected = ref<FormalRoleKey[]>([])
const options = [
  { label: '建筑业主', value: 'BUILDING_OWNER' }, { label: '能效管理方', value: 'ENERGY_MANAGER' },
  { label: '接口调用方', value: 'THIRD_PARTY' }, { label: '平台管理员', value: 'PLATFORM_ADMIN' },
] as const
watch(() => [props.open, props.user] as const, () => { selected.value = [...(props.user?.roles ?? [])] }, { immediate: true })
</script>
