<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElAlert } from '@/shared/ui'
import { t } from '@/locales'
import ChangeRequestControl from '../components/ChangeRequestControl.vue'
import { useSensitiveChange } from '../composables/use-sensitive-change'

const route = useRoute()
const changes = useSensitiveChange()

async function run(action: () => Promise<unknown>) {
  try { await action() } catch { /* 请求错误由 composable 展示。 */ }
}

function openQueryRequest() {
  const id = route.query.requestId
  if (typeof id === 'string' && id.trim()) void run(() => changes.load(id.trim()))
}

onMounted(openQueryRequest)
watch(() => route.query.requestId, openQueryRequest)
</script>

<template>
  <section class="change-page" :aria-label="t('accessControl.change.title')">
    <ElAlert v-if="changes.error.value" :title="changes.error.value" type="error" show-icon :closable="false" />
    <ChangeRequestControl
      inbox
      :change="changes.current.value"
      :busy="changes.pending.value.size > 0"
      @lookup="id => run(() => changes.load(id))"
      @submit="id => run(() => changes.submit(id))"
      @withdraw="id => run(() => changes.withdraw(id))"
      @approve="(id, comment) => run(() => changes.approve(id, comment))"
      @reject="(id, comment) => run(() => changes.reject(id, comment))"
      @execute="id => run(() => changes.execute(id))"
    />
  </section>
</template>

<style scoped>
.change-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
</style>
