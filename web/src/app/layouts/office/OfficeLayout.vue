<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElDrawer, ElMenu, ElMenuItem, ElSubMenu, ElPopover, Search, Bell, UserRound, Menu, Settings2 } from '@/shared/ui'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import PendingPage from '@/shared/components/PendingPage.vue'
import HeaderDivider from '@/shared/components/HeaderDivider.vue'
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
const mobileQuery = window.matchMedia('(max-width: 640px)')
const narrowScreen = ref(mobileQuery.matches)
const menuOpen = ref(false)
const moreOpen = ref(false)
const compactAction = ref<'search' | 'messages' | null>(null)
function syncViewport(event: MediaQueryListEvent) {
  narrowScreen.value = event.matches
  menuOpen.value = false
  moreOpen.value = false
}
onMounted(() => mobileQuery.addEventListener('change', syncViewport))
onBeforeUnmount(() => mobileQuery.removeEventListener('change', syncViewport))
watch(() => route.fullPath, () => { menuOpen.value = false; moreOpen.value = false })
const menuContainerProps = computed(() => narrowScreen.value ? {
  modelValue: menuOpen.value, 'onUpdate:modelValue': (open: boolean) => { menuOpen.value = open },
  title: t('workspaces.groups'), direction: 'ltr', size: 'min(320px, 90vw)',
} : { 'aria-label': t('workspaces.groups') })
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
      <ElButton v-if="narrowScreen" class="mobile-trigger" :icon="Menu" text :aria-label="t('workspaces.openMenu')" :aria-expanded="menuOpen" @click="menuOpen = true" />
      <WorkspaceBrand />
      <HeaderDivider />
      <div class="system-navigation">
        <SystemSwitcher plain :label="t(currentSystem?.titleKey ?? 'workspaces.select')" />
      </div>
      <div v-if="!narrowScreen" class="tools">
        <HeaderDivider />
        <template v-for="item in [{ key: 'search', icon: Search }, { key: 'messages', icon: Bell }]" :key="item.key">
          <ElPopover trigger="click" :teleported="false" width="var(--bec-navigation-width)">
            <template #reference><ElButton :icon="item.icon" :class="item.key === 'search' ? 'search-trigger' : 'icon-trigger'" text :aria-label="t('workspaces.' + item.key)" :title="t('workspaces.' + item.key)"><span v-if="item.key === 'search'">{{ t('workspaces.search') }}</span></ElButton></template>
            <PendingPage :title="t('workspaces.' + item.key)" />
          </ElPopover>
          <HeaderDivider />
        </template>
        <ElPopover trigger="click" :teleported="false" width="var(--bec-navigation-width)">
          <template #reference><ElButton class="user-trigger" text :icon="UserRound" :aria-label="t('workspaces.user')"><span class="username" :title="session.user?.username">{{ session.user?.username }}</span></ElButton></template>
          <ElButton @click="logout">{{ t('workspaces.logout') }}</ElButton>
        </ElPopover>
      </div>
      <ElPopover v-else v-model:visible="moreOpen" trigger="click" placement="bottom-end" width="var(--bec-navigation-width)" @show="compactAction = null">
        <template #reference><ElButton class="mobile-trigger more-trigger" :icon="Settings2" text :aria-label="t('workspaces.moreActions')" :aria-expanded="moreOpen" /></template>
        <div class="compact-tools">
          <span class="compact-username">{{ session.user?.username }}</span>
          <ElButton :icon="Search" text @click="compactAction = 'search'">{{ t('workspaces.search') }}</ElButton>
          <ElButton :icon="Bell" text @click="compactAction = 'messages'">{{ t('workspaces.messages') }}</ElButton>
          <ElButton :icon="UserRound" text @click="logout">{{ t('workspaces.logout') }}</ElButton>
          <PendingPage v-if="compactAction" :title="t('workspaces.' + compactAction)" />
        </div>
      </ElPopover>
    </header>
    <component :is="narrowScreen ? ElDrawer : 'aside'" v-bind="menuContainerProps">
      <ElMenu :key="String(route.meta.system)" :default-active="route.path" :default-openeds="activeGroups" unique-opened router @select="menuOpen = false">
        <ElSubMenu v-for="group in groups" :key="group" :index="group">
          <template #title><component :is="iconFor(group)" class="group-icon" aria-hidden="true" /><span class="menu-group">{{ t(group) }}</span></template>
          <ElMenuItem v-for="page in visiblePages.filter(item => item.groupKey === group)" :key="page.id" :index="page.path" :aria-current="route.path === page.path ? 'page' : undefined"><span class="menu-label" :title="page.title ?? t(page.titleKey)">{{ page.title ?? t(page.titleKey) }}</span></ElMenuItem>
        </ElSubMenu>
      </ElMenu>
    </component>
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
.system-navigation { display: flex; align-items: center; gap: var(--bec-space-tight); }
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
.workspace-header > .workspace-brand { flex-shrink: 1; min-width: 0; }
.workspace-header :deep(.workspace-brand strong) { overflow: hidden; text-overflow: ellipsis; }
.system-navigation, .tools { flex-shrink: 0; }
.compact-tools { display: grid; gap: var(--bec-space-tight); }
.compact-tools :deep(.el-button) { margin-left: 0; justify-content: flex-start; }
.compact-username { overflow-wrap: anywhere; }
@media (max-width: 1200px) {
  .workspace-header :deep(.workspace-brand .company-logo),
  .workspace-header :deep(.workspace-brand .header-divider),
  .tools :deep(.header-divider), .search-trigger :deep(span), .username { display: none; }
  .search-trigger, .user-trigger { width: var(--bec-control-height); padding: 0; }
}
@media (max-width: 640px) {
  .office-layout { grid-template-columns: minmax(0, 1fr); }
  .workspace-header { padding-inline: var(--bec-space-group); }
  .workspace-header > .header-divider { display: none; }
  .workspace-header > .workspace-brand { flex: 1; }
  .workspace-header :deep(.workspace-brand .company-logo) { display: grid; width: min(100%, var(--bec-company-logo-width)); }
  .workspace-header :deep(.workspace-brand strong), .system-navigation :deep(.switcher-label) { display: none; }
  .mobile-trigger, .system-navigation :deep(.el-button) { width: calc(var(--bec-control-height) + var(--bec-space-group)); height: calc(var(--bec-control-height) + var(--bec-space-group)); padding: 0; margin: 0; flex-shrink: 0; }
  main { padding: var(--bec-space-group); }
}
</style>
