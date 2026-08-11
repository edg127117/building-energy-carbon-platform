<template>
  <a-drawer :open="open" title="直接分配建筑" width="440" @close="$emit('close')">
    <a-alert type="warning" show-icon message="保存会立即全量替换该用户的建筑访问范围。" />
    <a-select v-model:value="selected" mode="multiple" class="assignment-select" :options="options" placeholder="选择建筑" />
    <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" :loading="submitting" @click="$emit('save', selected)">保存授权</a-button></div>
  </a-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BuildingOption, UserAdminView } from '@/types/admin'
/** 直接授权提交完整建筑 ID 集合；建筑档案本身只读，不在此组件维护。 */
const props = defineProps<{ open: boolean; user?: UserAdminView | null; buildings: BuildingOption[]; submitting?: boolean }>()
defineEmits<{ close: []; save: [buildingIds: string[]] }>()
const selected = ref<string[]>([])
const options = computed(() => props.buildings.map((item) => ({ label: `${item.buildingName} · ${item.buildingCode ?? item.buildingId}`, value: item.buildingId })))
watch(() => [props.open, props.user] as const, () => { selected.value = [...(props.user?.buildingIds ?? [])] }, { immediate: true })
</script>
