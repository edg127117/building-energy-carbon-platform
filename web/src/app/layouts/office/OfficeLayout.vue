<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMenu, ElMenuItem, ElSubMenu, ElPopover, Search, Bell, UserRound } from '@/shared/ui'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import PendingPage from '@/shared/components/PendingPage.vue'
import SystemSwitcher from '@/app/navigation/SystemSwitcher.vue'
import WorkspaceBrand from '@/app/navigation/WorkspaceBrand.vue'
import { groupIcons } from '@/app/navigation/icons'
import { authorizedPages, workspaces } from '@/app/navigation/catalog'
import { useSession } from '@/modules/auth/public'
import { useShellStore } from '@/app/providers/shell-store'
import { t } from '@/locales'
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
const iconFor = (group: string) => groupIcons[group as keyof typeof groupIcons]
async function logout() {
  try { await session.logout(); await router.replace('/login') } catch { logoutError.value = true }
}
</script>
<template>
  <div class="office-layout management-surface" data-page-mode="office">
    <a class="skip-link" href="#platform-content" @click.prevent="content?.focus()">{{ t('navigation.skipContent') }}</a>
    <header class="workspace-header">
      <WorkspaceBrand />
      <div class="system-navigation">
        <span class="current-system">{{ t(currentSystem?.titleKey ?? 'workspaces.select') }}</span>
        <SystemSwitcher plain />
      </div>
      <div class="tools">
        <ElPopover v-for="item in [{ key: 'search', icon: Search }, { key: 'messages', icon: Bell }]" :key="item.key" trigger="click" :teleported="false" width="var(--bec-navigation-width)">
          <template #reference><ElButton :icon="item.icon" :class="item.key === 'search' ? 'search-trigger' : 'icon-trigger'" text :aria-label="t('workspaces.' + item.key)" :title="t('workspaces.' + item.key)"><span v-if="item.key === 'search'">{{ t('workspaces.search') }}</span></ElButton></template>
          <PendingPage :title="t('workspaces.' + item.key)" />
        </ElPopover>
        <ElPopover trigger="click" :teleported="false" width="var(--bec-navigation-width)">
          <template #reference><ElButton class="user-trigger" text :icon="UserRound" :aria-label="t('workspaces.user')"><span class="username" :title="session.user?.username">{{ session.user?.username }}</span></ElButton></template>
          <ElButton @click="logout">{{ t('workspaces.logout') }}</ElButton>
        </ElPopover>
      </div>
    </header>
    <aside :aria-label="t('workspaces.groups')">
      <ElMenu :key="String(route.meta.system)" :default-active="route.path" :default-openeds="activeGroups" unique-opened router>
        <ElSubMenu v-for="group in groups" :key="group" :index="group">
          <template #title><component :is="iconFor(group)" class="group-icon" aria-hidden="true" /><span class="menu-group">{{ t(group) }}</span></template>
          <ElMenuItem v-for="page in visiblePages.filter(item => item.groupKey === group)" :key="page.id" :index="page.path" :aria-current="route.path === page.path ? 'page' : undefined"><span class="menu-label" :title="page.title ?? t(page.titleKey)">{{ page.title ?? t(page.titleKey) }}</span></ElMenuItem>
        </ElSubMenu>
      </ElMenu>
    </aside>
    <main id="platform-content" ref="content" tabindex="-1" :aria-label="t('navigation.content')" :data-page-path="route.path">
      <ElAlert v-if="shell.navigationFailed" :title="t('error.page')" type="error" :closable="false" />
      <ElAlert v-if="shell.offline" :title="t('error.offline')" type="warning" :closable="false" />
      <ElAlert v-if="logoutError" :title="t('workspaces.logoutFailed')" type="error" :closable="false" />
      <div class="page-content"><RouterView v-slot="{ Component }"><StateBoundary :key="route.path"><component :is="Component" /></StateBoundary></RouterView></div>
    </main>
  </div>
</template>
<style scoped>
.office-layout { height: 100%; display: grid; grid-template-columns: var(--bec-navigation-width) minmax(0, 1fr); grid-template-rows: var(--bec-workspace-header-height) minmax(0, 1fr); background: var(--bec-management-background); }
.workspace-header { grid-column: 1 / -1; display: flex; align-items: center; gap: var(--bec-space-tight); padding: 0 var(--bec-space-page); background: var(--bec-workspace-header-background); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); box-shadow: var(--bec-shadow-card); position: relative; z-index: var(--bec-layer-navigation); }
.system-navigation { display: flex; align-items: center; gap: var(--bec-space-group); padding-left: var(--bec-space-group); margin-left: var(--bec-space-tight); border-left: var(--bec-border-width) solid var(--bec-color-divider); }
.current-system { padding: var(--bec-ref-space-4) var(--bec-ref-space-12); border-radius: var(--bec-management-radius); background: var(--bec-workspace-system-background); white-space: nowrap; color: var(--bec-color-action-active); font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-normal); }
.tools { display: flex; align-items: center; gap: var(--bec-ref-space-4); margin-left: auto; min-width: 0; }
.username { display: block; max-width: var(--bec-workspace-user-width); overflow: hidden; text-overflow: ellipsis; }
.search-trigger { background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-tag); }
.icon-trigger { width: var(--bec-control-height); padding: 0; }
.user-trigger { padding: 0 var(--bec-space-tight); }
.menu-group { font-size: var(--bec-management-menu-font-size); }
.group-icon { flex-shrink: 0; width: var(--bec-icon-small); height: var(--bec-icon-small); margin-right: var(--bec-space-section); color: var(--bec-color-text-secondary); stroke-width: 1.5; }
.menu-label { overflow: hidden; text-overflow: ellipsis; }
aside { overflow-y: auto; padding: var(--bec-space-group) var(--bec-space-tight); background: var(--bec-color-surface); border-right: var(--bec-border-width) solid var(--bec-color-divider); }
main { display: flex; flex-direction: column; gap: var(--bec-space-group); min-width: 0; min-height: 0; overflow: auto; padding: var(--bec-space-page); }
.page-content { flex: 1; min-height: 0; }
.skip-link { position: absolute; transform: translateY(-200%); }
.skip-link:focus { transform: none; padding: var(--bec-space-tight); background: var(--bec-color-surface); }
</style>
