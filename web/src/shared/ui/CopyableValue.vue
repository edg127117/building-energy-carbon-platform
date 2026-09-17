<script setup lang="ts">
import { ref } from 'vue'
import { t } from '@/locales'
const props = defineProps<{ value: string }>()
// 剪贴板不可用时保留原值与手动选择能力，不把复制失败当成业务操作失败。
const copied = ref(false)
const failed = ref(false)
async function copy() {
  try { await navigator.clipboard.writeText(props.value); copied.value = true; failed.value = false }
  catch { failed.value = true }
}
</script>
<template>
  <span class="copyable-value"><code>{{ value }}</code><button type="button" @click="copy">{{ t(copied ? 'common.copied' : 'common.copy') }}</button><span v-if="failed" role="status">{{ t('common.copyFailed') }}</span></span>
</template>
<style scoped>
.copyable-value { display: flex; align-items: baseline; flex-wrap: wrap; gap: var(--bec-space-tight); min-width: 0; }
code { overflow-wrap: anywhere; user-select: all; }
button { color: var(--bec-color-action-primary); cursor: pointer; border: 0; background: transparent; font: inherit; }
</style>
