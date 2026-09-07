<script setup lang="ts">
import { onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElConfigProvider, ElAlert, ElButton, zhCn } from '@/shared/ui'
import { t } from '@/locales'
import { useShellStore } from './providers/shell-store'
const router = useRouter()
const route = useRoute()
const shell = useShellStore()
// 异步页面导入失败时当前外壳保持可用，不暴露内部路径和加载异常。
const removeError = router.onError(() => { shell.navigationFailed = true })
const removeAfter = router.afterEach((to, _from, failure) => {
  if (failure) return
  shell.navigationFailed = false
  document.title = t(String(to.meta.titleKey ?? 'terminology.systemName'))
})
watch(() => route.fullPath, () => { shell.navigationFailed = false })
onUnmounted(() => { removeError(); removeAfter() })
</script>
<template>
  <ElConfigProvider :locale="zhCn">
    <section v-if="shell.navigationFailed && !route.matched.length" class="initial-error" role="alert">
      <ElAlert :title="t('error.page')" type="error" :closable="false" />
      <ElButton @click="router.push('/office')">{{ t('navigation.returnOffice') }}</ElButton>
    </section>
    <RouterView v-else />
  </ElConfigProvider>
</template>
<style scoped>
.initial-error { display: grid; gap: var(--bec-space-group); padding: var(--bec-space-page); }
</style>
