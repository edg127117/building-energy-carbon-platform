<script setup lang="ts">
import { computed } from 'vue'
import { useSession } from '@/modules/auth/public'
import { ElPopover, ElButton, LayoutGrid } from '@/shared/ui'
import { t } from '@/locales'
import { authorizedPages, workspaces } from './catalog'
const session = useSession()
const available = computed(() => workspaces.filter(system => authorizedPages(session.menus).some(page => page.system === system.id)))
</script>
<template>
  <ElPopover trigger="click" :teleported="false" width="var(--bec-navigation-width)">
    <template #reference><ElButton :icon="LayoutGrid">{{ t('workspaces.switch') }}</ElButton></template>
    <nav class="switcher" :aria-label="t('workspaces.switch')">
      <RouterLink v-for="system in available" :key="system.id" :to="authorizedPages(session.menus).find(page => page.system === system.id)!.path">{{ t(system.titleKey) }}</RouterLink>
      <RouterLink to="/systems">{{ t('workspaces.select') }}</RouterLink>
    </nav>
  </ElPopover>
</template>
<style scoped>
.switcher { display: grid; gap: var(--bec-space-tight); }
a { display: block; padding: var(--bec-space-tight); color: var(--bec-color-text-primary); text-decoration: none; }
a:hover { color: var(--bec-color-action-primary); background: var(--bec-color-surface-secondary); }
</style>
