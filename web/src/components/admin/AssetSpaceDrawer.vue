<template>
  <a-drawer :open="open" :title="space ? '编辑空间' : parentSpace ? '新建下级空间' : '新建空间'" width="460" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="空间名称" required><a-input v-model:value="form.spaceName" maxlength="100" /></a-form-item>
      <a-form-item label="上级空间"><a-select v-model:value="form.parentSpaceId" allow-clear :options="parentOptions" placeholder="顶层空间" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="空间编码"><a-input v-model:value="form.spaceCode" maxlength="64" /></a-form-item><a-form-item label="空间类型"><a-input v-model:value="form.spaceType" maxlength="64" /></a-form-item></div>
      <a-form-item label="可用面积（㎡）"><a-input-number v-model:value="form.usableArea" :min="0" style="width:100%" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="排序"><a-input-number v-model:value="form.sortOrder" :min="0" style="width:100%" /></a-form-item><a-form-item label="档案状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item></div>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存空间</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { AssetSpaceForm, AssetSpaceView } from '@/types/assets'

/** 空间抽屉保持 buildingId 只读归属，父级选项来自同一建筑的现有树。 */
const props = defineProps<{ open: boolean; buildingId: string; space?: AssetSpaceView | null; parentSpace?: AssetSpaceView | null; spaces: AssetSpaceView[]; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: AssetSpaceForm] }>()
const form = reactive({ spaceName: '', parentSpaceId: undefined as string | undefined, spaceCode: '', spaceType: '', usableArea: undefined as number | undefined, sortOrder: 0, status: 'ACTIVE' })
const statusOptions = [{ label: '启用', value: 'ACTIVE' }]
const parentOptions = computed(() => flatten(props.spaces).filter((item) => item.spaceId !== props.space?.spaceId).map((item) => ({ label: item.spaceName, value: item.spaceId })))

watch(() => [props.open, props.space, props.parentSpace, props.buildingId] as const, () => Object.assign(form, {
  spaceName: props.space?.spaceName ?? '', parentSpaceId: props.space?.parentSpaceId ?? props.parentSpace?.spaceId,
  spaceCode: props.space?.spaceCode ?? '', spaceType: props.space?.spaceType ?? '', usableArea: props.space?.usableArea ?? undefined, sortOrder: props.space?.sortOrder ?? 0, status: props.space?.status ?? 'ACTIVE',
}), { immediate: true })

function submit() {
  if (!props.buildingId || !form.spaceName.trim()) return
  emit('save', { buildingId: props.buildingId, parentSpaceId: form.parentSpaceId ?? null, spaceName: form.spaceName.trim(), spaceCode: nullable(form.spaceCode), spaceType: nullable(form.spaceType), usableArea: form.usableArea ?? null, sortOrder: form.sortOrder, status: form.status })
}
function flatten(items: AssetSpaceView[]): AssetSpaceView[] { return items.flatMap((item) => [item, ...flatten(item.children)]) }
function nullable(value: string) { return value.trim() || null }
</script>

<style scoped>.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }</style>
