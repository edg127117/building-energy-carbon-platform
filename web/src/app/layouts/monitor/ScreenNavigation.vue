<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElDialog, ElMenu, ElMenuItem, ElSubMenu } from '@/shared/ui'
import { screens } from '@/modules/large-screen/public'
import { screenGroups } from '@/shared/models/screen-registration'
import { t } from '@/locales'
const visible = defineModel<boolean>({ required: true })
const router = useRouter()
const route = useRoute()
const groups = computed(() => screenGroups(screens))
async function select(path: string) {
  visible.value = false
  try { await router.push(path) } catch {
    // 应用层 router.onError 统一显示加载失败，事件处理器不再泄漏原始异常。
  }
}
</script>
<template>
  <ElDialog
    v-model="visible" :title="t('navigation.switchScreen')" width="var(--bec-dialog-width)"
    :append-to-body="false" :close-on-click-modal="true" destroy-on-close
  >
    <ElMenu :default-active="route.path" :default-openeds="groups.map(group => group.key ?? 'unassigned')" :aria-label="t('navigation.switchScreen')" @select="select">
      <ElSubMenu v-for="group in groups" :key="group.key ?? 'unassigned'" :index="group.key ?? 'unassigned'">
        <template #title>{{ t(group.key ?? 'navigation.ungrouped') }}</template>
        <ElMenuItem v-for="screen in group.entries" :key="screen.id" :index="screen.path">
          {{ t(screen.titleKey) }}
        </ElMenuItem>
      </ElSubMenu>
    </ElMenu>
  </ElDialog>
</template>
