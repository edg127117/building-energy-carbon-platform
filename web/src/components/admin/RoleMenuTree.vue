<template>
  <a-tree :tree-data="tree" checkable :default-expanded-keys="[]" :checked-keys="checkedIds" :field-names="fieldNames" @check="handleCheck">
    <template #title="node"><span>{{ node.menuName }}</span><a-tag v-if="node.status === 0" color="orange">停用</a-tag><a-tag v-if="node.visible === 0">隐藏</a-tag></template>
  </a-tree>
</template>
<script setup lang="ts">
import type { MenuNode } from '@/types/admin'
/** 完整菜单树的纯选择组件；保存时由 Composable 规范化并提交完整 ID 集合。 */
defineProps<{ tree: MenuNode[]; checkedIds: number[] }>()
const emit = defineEmits<{ 'update:checkedIds': [ids: number[]] }>()
const fieldNames = { title: 'menuName', key: 'id', children: 'children' }
function handleCheck(keys: string[] | number[] | { checked: Array<string | number> }) {
  const values = Array.isArray(keys) ? keys : keys.checked
  emit('update:checkedIds', values.map(Number))
}
</script>
