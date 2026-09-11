<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ElTree } from '@/shared/ui'
import type { MenuNode } from '../models/access-control'

const props = defineProps<{ tree: MenuNode[]; checkedIds: number[] }>()
const emit = defineEmits<{ 'update:checkedIds': [ids: number[]] }>()
const treeProps = { label: 'menuName', children: 'children' }
const treeRef = ref<{ setCheckedKeys: (ids: number[]) => void } | null>(null)

/** 异步切换角色后显式同步勾选集合，避免树组件只在初次挂载时读取默认值。 */
watch(() => props.checkedIds, ids => {
  void nextTick(() => treeRef.value?.setCheckedKeys(ids))
}, { deep: true })

function onCheck(_node: MenuNode, state: { checkedKeys: Array<number | string> }) {
  emit('update:checkedIds', state.checkedKeys.map(Number))
}
</script>

<template>
  <ElTree ref="treeRef" :data="tree" node-key="id" show-checkbox :default-checked-keys="checkedIds" :props="treeProps" @check="onCheck" />
</template>
