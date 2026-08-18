<template>
  <a-tag :color="presentation.color" :title="presentation.known ? undefined : '服务端返回了未识别状态，已切换为只读安全态'">
    {{ presentation.label }}
  </a-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { presentAssetStatus, type AssetStatus } from '@/types/assets'

/** 状态展示集中处理枚举演进，未知值不在各业务页面分散推断。 */
const props = defineProps<{ status: AssetStatus | null | undefined }>()
const presentation = computed(() => presentAssetStatus(props.status))
</script>
