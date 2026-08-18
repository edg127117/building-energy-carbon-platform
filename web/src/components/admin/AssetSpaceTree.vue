<template>
  <ul class="asset-space-tree" role="tree" aria-label="建筑空间树">
    <li v-for="space in items" :key="space.spaceId" role="treeitem">
      <div class="asset-space-tree-row">
        <div class="asset-space-tree-name"><strong>{{ space.spaceName }}</strong><small>{{ space.spaceType || '未分类空间' }}{{ space.spaceCode ? ` · ${space.spaceCode}` : '' }}</small></div>
        <AssetStatusTag :status="space.status" />
        <a-space size="small">
          <a-button type="link" size="small" :disabled="!canManage(space, 'CREATE')" @click="$emit('add-child', space)">下级</a-button>
          <a-button type="link" size="small" :disabled="!canManage(space, 'UPDATE')" @click="$emit('edit', space)">编辑</a-button>
          <a-button type="link" danger size="small" :disabled="!canManage(space, 'DELETE')" @click="$emit('remove', space)">删除</a-button>
        </a-space>
      </div>
      <AssetSpaceTree v-if="space.children.length" :items="space.children" @add-child="$emit('add-child', $event)" @edit="$emit('edit', $event)" @remove="$emit('remove', $event)" />
    </li>
  </ul>
</template>

<script setup lang="ts">
import AssetStatusTag from './AssetStatusTag.vue'
import { canRunAssetAction, type AssetAllowedAction, type AssetSpaceView } from '@/types/assets'

/** 递归树只组织空间层级和用户动作；新增、删除和权限校验仍交给页面与后端。 */
defineProps<{ items: AssetSpaceView[] }>()
defineEmits<{ 'add-child': [space: AssetSpaceView]; edit: [space: AssetSpaceView]; remove: [space: AssetSpaceView] }>()
const canManage = (space: AssetSpaceView, action: AssetAllowedAction) => canRunAssetAction(space, action)
</script>

<style scoped>
.asset-space-tree { margin: 0; padding: 0; list-style: none; }
.asset-space-tree .asset-space-tree { margin: 6px 0 0 24px; padding-left: 14px; border-left: 1px solid rgba(120, 169, 207, .18); }
.asset-space-tree-row { min-height: 46px; display: flex; align-items: center; gap: 10px; padding: 6px 0; }
.asset-space-tree-name { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; }
.asset-space-tree-name strong { overflow: hidden; color: #dbeaf5; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.asset-space-tree-name small { color: #7f98ad; font-size: 11px; }
</style>
