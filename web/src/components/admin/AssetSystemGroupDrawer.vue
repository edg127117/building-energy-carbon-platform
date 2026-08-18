<template>
  <a-drawer :open="open" :title="systemGroup ? '编辑系统分组' : '新建系统分组'" width="460" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="系统名称" required><a-input v-model:value="form.systemName" maxlength="100" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="系统编码"><a-input v-model:value="form.systemCode" maxlength="64" /></a-form-item><a-form-item label="系统类型"><a-input v-model:value="form.systemType" maxlength="64" /></a-form-item></div>
      <a-form-item label="档案状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存分组</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { AssetSystemGroupForm, AssetSystemGroupView } from '@/types/assets'

/** 系统分组仅在当前建筑上下文编辑，跨建筑归属不能由浏览器改写。 */
const props = defineProps<{ open: boolean; buildingId: string; systemGroup?: AssetSystemGroupView | null; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: AssetSystemGroupForm] }>()
const form = reactive({ systemName: '', systemCode: '', systemType: '', status: 'ACTIVE' })
const statusOptions = [{ label: '启用', value: 'ACTIVE' }]
watch(() => [props.open, props.systemGroup] as const, () => Object.assign(form, {
  systemName: props.systemGroup?.systemName ?? '', systemCode: props.systemGroup?.systemCode ?? '', systemType: props.systemGroup?.systemType ?? '',
  status: props.systemGroup?.status ?? 'ACTIVE',
}), { immediate: true })
function submit() { if (!props.buildingId || !form.systemName.trim()) return; emit('save', { buildingId: props.buildingId, systemName: form.systemName.trim(), systemCode: nullable(form.systemCode), systemType: nullable(form.systemType), status: form.status }) }
function nullable(value: string) { return value.trim() || null }
</script>

<style scoped>.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }</style>
