<script setup lang="ts">
import { computed, onErrorCaptured, ref, watch } from 'vue'
import { ElAlert, ElButton, ElEmpty, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import type { PageState } from '@/shared/models/page-state'
const props = withDefaults(defineProps<{ state?: PageState; resetKey?: string }>(), { state: 'ready', resetKey: '' })
const emit = defineEmits<{ retry: [] }>()
const failed = ref(false)
const revision = ref(0)
const current = computed(() => failed.value ? 'error' : props.state)
// 只隔离子树渲染错误，不吞掉后端错误，也不改变其权限或业务状态。
onErrorCaptured(() => { failed.value = true; return false })
watch(() => props.resetKey, () => { failed.value = false })
function retry() { failed.value = false; revision.value++; emit('retry') }
const message = computed(() => t(current.value === 'forbidden' ? 'error.forbidden'
  : current.value === 'not-found' ? 'error.notFound' : 'error.page'))
</script>

<template>
  <section class="state-boundary" :aria-busy="current === 'loading'">
    <template v-if="current === 'loading'">
      <span role="status">{{ t('common.loading') }}</span><ElSkeleton animated />
    </template>
    <ElEmpty v-else-if="current === 'empty'" :description="t('common.empty')" />
    <template v-else-if="current !== 'ready'">
      <ElAlert :title="message" type="error" show-icon :closable="false" />
      <ElButton v-if="current === 'error'" @click="retry">{{ t('common.retry') }}</ElButton>
    </template>
    <slot v-else :key="revision" />
  </section>
</template>

<style scoped>
.state-boundary { min-width: 0; min-height: 0; height: 100%; }
</style>
