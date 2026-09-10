<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMenu, ElMenuItem, ElSubMenu, ElPopover, Search, Bell, UserRound } from '@/shared/ui'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import PendingPage from '@/shared/components/PendingPage.vue'
import SystemSwitcher from '@/app/navigation/SystemSwitcher.vue'
import { authorizedPages, workspaces } from '@/app/navigation/catalog'
import { useSession } from '@/modules/auth/public'
import { useShellRuntime } from '@/app/providers/runtime'
import { useShellStore } from '@/app/providers/shell-store'
import { formatDateTime } from '@/shared/utils/format'
import { t } from '@/locales'
const { now } = useShellRuntime()
const shell = useShellStore()
const session = useSession()
const route = useRoute()
const router = useRouter()
const content = ref<HTMLElement | null>(null)
const logoutError = ref(false)
const currentSystem = computed(() => workspaces.find(system => system.id === route.meta.system))
const visiblePages = computed(() => authorizedPages(session.menus).filter(page => page.system === route.meta.system))
const groups = computed(() => [...new Set(visiblePages.value.map(page => page.groupKey))])
const activeGroups = computed(() => visiblePages.value.filter(page => page.path === route.path).map(page => page.groupKey))
async function logout() {
  try { await session.logout(); await router.replace('/login') } catch { logoutError.value = true }
}
</script>
<template>
  <div class="office-layout" data-page-mode="office">
    <a class="skip-link" href="#platform-content" @click.prevent="content?.focus()">{{ t('navigation.skipContent') }}</a>
    <header class="workspace-header">
      <div class="identity"><span class="brand-placeholder">{{ t('common.companyLogo') }}</span><strong>{{ t(currentSystem?.titleKey ?? 'workspaces.select') }}</strong></div>
      <SystemSwitcher />
      <div class="tools">
        <time :datetime="now.toISOString()">{{ formatDateTime(now) }}</time>
        <ElPopover v-for="item in [{ key: 'search', icon: Search }, { key: 'messages', icon: Bell }]" :key="item.key" trigger="click" :teleported="false" width="var(--bec-navigation-width)">
          <template #reference><ElButton :icon="item.icon" :aria-label="t('workspaces.' + item.key)">{{ t('workspaces.' + item.key) }}</ElButton></template>
          <PendingPage :title="t('workspaces.' + item.key)" />
        </ElPopover>
        <ElPopover trigger="click" :teleported="false" width="var(--bec-navigation-width)">
          <template #reference><ElButton :icon="UserRound" :aria-label="t('workspaces.user')"><span class="username" :title="session.user?.username">{{ session.user?.username }}</span></ElButton></template>
          <ElButton @click="logout">{{ t('workspaces.logout') }}</ElButton>
        </ElPopover>
      </div>
    </header>
    <aside :aria-label="t('workspaces.groups')">
      <ElMenu :key="String(route.meta.system)" :default-active="route.path" :default-openeds="activeGroups" unique-opened router>
        <ElSubMenu v-for="group in groups" :key="group" :index="group">
          <template #title><span class="menu-group">{{ t(group) }}</span></template>
          <ElMenuItem v-for="page in visiblePages.filter(item => item.groupKey === group)" :key="page.id" :index="page.path">{{ page.title ?? t(page.titleKey) }}</ElMenuItem>
        </ElSubMenu>
      </ElMenu>
    </aside>
    <main id="platform-content" ref="content" tabindex="-1" :aria-label="t('navigation.content')" :data-page-path="route.path">
      <ElAlert v-if="shell.navigationFailed" :title="t('error.page')" type="error" :closable="false" />
      <ElAlert v-if="shell.offline" :title="t('error.offline')" type="warning" :closable="false" />
      <ElAlert v-if="logoutError" :title="t('workspaces.logoutFailed')" type="error" :closable="false" />
      <RouterView v-slot="{ Component }"><StateBoundary :key="route.path"><component :is="Component" /></StateBoundary></RouterView>
    </main>
  </div>
</template>
<style scoped>
.office-layout { height: 100%; display: grid; grid-template-columns: var(--bec-navigation-width) minmax(0, 1fr); grid-template-rows: var(--bec-workspace-header-height) minmax(0, 1fr); background: var(--bec-color-page); }
.workspace-header { grid-column: 1 / -1; display: flex; align-items: center; gap: var(--bec-space-group); padding: 0 var(--bec-space-page); background: var(--bec-color-surface); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
.identity { display: flex; align-items: center; gap: var(--bec-space-group); min-width: 0; }
strong { white-space: nowrap; font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
.brand-placeholder { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); white-space: nowrap; }
.tools { display: flex; align-items: center; gap: var(--bec-space-tight); margin-left: auto; min-width: 0; }
time { white-space: nowrap; color: var(--bec-color-text-secondary); font-variant-numeric: tabular-nums; }
.username { display: block; max-width: var(--bec-workspace-user-width); overflow: hidden; text-overflow: ellipsis; }
.menu-group { font-size: var(--bec-font-size-navigation); }
aside { overflow-y: auto; background: var(--bec-color-surface); border-right: var(--bec-border-width) solid var(--bec-color-divider); }
main { min-width: 0; min-height: 0; overflow: auto; padding: var(--bec-space-page); }
.skip-link { position: absolute; transform: translateY(-200%); }
.skip-link:focus { transform: none; padding: var(--bec-space-tight); background: var(--bec-color-surface); }
</style>
