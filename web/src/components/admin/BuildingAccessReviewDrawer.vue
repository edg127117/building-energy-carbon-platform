<template>
  <a-drawer :open="open" title="审核建筑访问申请" width="480" @close="$emit('close')">
    <a-descriptions v-if="request" bordered :column="1" size="small">
      <a-descriptions-item label="申请人">{{ request.username ?? request.userId }}</a-descriptions-item><a-descriptions-item label="建筑">{{ request.buildingName ?? request.buildingId }}</a-descriptions-item>
      <a-descriptions-item label="申请原因">{{ request.reason }}</a-descriptions-item><a-descriptions-item label="状态">{{ request.status }}</a-descriptions-item>
    </a-descriptions>
    <a-form-item label="审核意见" class="review-comment"><a-textarea v-model:value="comment" :maxlength="500" :rows="4" /></a-form-item>
    <div class="drawer-actions"><a-button @click="$emit('close')">关闭</a-button><template v-if="request?.status === 'PENDING'"><a-button danger :loading="submitting" @click="$emit('reject', comment.trim() || undefined)">拒绝</a-button><a-button type="primary" :loading="submitting" @click="$emit('approve', comment.trim() || undefined)">批准</a-button></template></div>
  </a-drawer>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import type { BuildingAccessRequestView } from '@/types/admin'
/** 已处理申请保持只读；待审申请的批准/拒绝各自只提交一次可选意见。 */
const props = defineProps<{ open: boolean; request?: BuildingAccessRequestView | null; submitting?: boolean }>()
defineEmits<{ close: []; approve: [comment?: string]; reject: [comment?: string] }>()
const comment = ref('')
watch(() => [props.open, props.request] as const, () => { comment.value = props.request?.reviewComment ?? '' }, { immediate: true })
</script>
