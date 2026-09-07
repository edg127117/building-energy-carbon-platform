<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElAside, ElButton, ElContainer, ElHeader, ElMain, ElMenu, ElMenuItem,
  ElSkeleton, LogOut, Monitor,
} from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import ScreenNavigation from '@/app/layouts/monitor/ScreenNavigation.vue'
import { useShellRuntime } from '@/app/providers/runtime'
import { useShellStore } from '@/app/providers/shell-store'
import {
  buildCurrentMenuNavigation,
  useCurrentMenuStore,
  type CurrentMenuItem,
  type MenuRouteRegistration,
} from '@/modules/access-control/public'
import { useAuthStore } from '@/modules/auth/public'
import { t } from '@/locales'

useShellRuntime()
const shell = useShellStore()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const menu = useCurrentMenuStore()
const navigationOpen = ref(false)
const content = ref<HTMLElement | null>(null)

const registrations: readonly MenuRouteRegistration[] = [
  { path: '/system/users', routeName: 'system-users' },
  { path: '/system/roles', routeName: 'system-roles' },
  { path: '/system/menus', routeName: 'system-menus' },
  { path: '/system/building-access', routeName: 'system-building-access' },
  { path: '/system/buildings', routeName: 'asset-archive' },
  { path: '/system/devices', routeName: 'equipment-points' },
  { path: '/system/device-products', routeName: 'device-products' },
  { path: '/system/device-onboarding', routeName: 'pending-devices' },
]

const managementItems = computed(() => {
  const navigation = buildCurrentMenuNavigation(menu.tree, registrations)
  return navigation.flatMap(flattenNavigation)
})
const accountName = computed(() => auth.user?.username || '')

function flattenNavigation(item: CurrentMenuItem): CurrentMenuItem[] {
  return [...(item.path ? [item] : []), ...item.children.flatMap(flattenNavigation)]
}

function retryMenu() {
  void menu.reload().catch(() => undefined)
}

async function logout() {
  await auth.logout().catch(() => undefined)
  menu.clear()
  await router.replace('/login')
}

onMounted(() => {
  void menu.ensureLoaded().catch(() => undefined)
})
</script>

<template>
  <ElContainer class="office-layout" data-page-mode="office">
    <a class="skip-link" href="#platform-content" @click.prevent="content?.focus()">{{ t('navigation.skipContent') }}</a>
    <ElHeader class="office-header">
      <BrandIdentity />
      <div class="account-actions">
        <span class="account"><strong>{{ accountName }}</strong><small>{{ t('navigation.administrator') }}</small></span>
        <ElButton :icon="Monitor" @click="navigationOpen = true">{{ t('navigation.switchScreen') }}</ElButton>
        <ElButton :icon="LogOut" @click="logout">{{ t('navigation.logout') }}</ElButton>
      </div>
    </ElHeader>
    <ElContainer class="office-body">
      <ElAside class="office-navigation" width="var(--bec-navigation-width)">
        <nav :aria-label="t('navigation.navigation')">
          <p class="navigation-heading">{{ t('navigation.business') }}</p>
          <ElMenu router :default-active="route.path">
            <ElMenuItem index="/office/dashboard">{{ t('dashboard.title') }}</ElMenuItem>
            <ElMenuItem index="/office/trends">{{ t('trendAnalysis.title') }}</ElMenuItem>
          </ElMenu>
          <template v-if="auth.isPlatformAdmin">
            <p class="navigation-heading">{{ t('navigation.administration') }}</p>
            <ElSkeleton v-if="menu.loading && !menu.loaded" :rows="4" animated />
            <ElAlert v-else-if="menu.error && !menu.loaded" :title="menu.error" type="warning" :closable="false">
              <ElButton text @click="retryMenu">{{ t('navigation.menuRetry') }}</ElButton>
            </ElAlert>
            <p v-else-if="managementItems.length === 0" class="navigation-empty">{{ t('navigation.menuEmpty') }}</p>
            <ElMenu v-else router :default-active="route.path">
              <ElMenuItem v-for="item in managementItems" :key="item.path" :index="item.path!">{{ item.label }}</ElMenuItem>
            </ElMenu>
          </template>
        </nav>
      </ElAside>
      <ElMain id="platform-content" ref="content" class="office-content" tabindex="-1" :aria-label="t('navigation.content')">
        <ElAlert v-if="shell.navigationFailed" :title="t('error.page')" type="error" show-icon :closable="false" />
        <ElAlert v-if="shell.offline" :title="t('error.offline')" type="warning" show-icon :closable="false" />
        <RouterView v-slot="{ Component }">
          <StateBoundary :key="route.path" :reset-key="route.path"><component :is="Component" /></StateBoundary>
        </RouterView>
      </ElMain>
    </ElContainer>
    <ScreenNavigation v-model="navigationOpen" />
  </ElContainer>
</template>

<style scoped>
.office-layout { min-height: 100%; background: var(--bec-color-page); }
.office-header { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); height: auto; padding: var(--bec-header-padding) var(--bec-space-page); background: var(--bec-color-surface); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
.office-body { min-width: 0; }
.office-navigation { padding: var(--bec-space-section) var(--bec-space-group); background: var(--bec-color-surface); border-right: var(--bec-border-width) solid var(--bec-color-divider); }
.office-content { display: grid; align-content: start; gap: var(--bec-space-section); padding: var(--bec-space-page); }
.account-actions, .account { display: flex; align-items: center; gap: var(--bec-space-group); }
.account { gap: var(--bec-space-tight); }
.account small, .navigation-empty { color: var(--bec-color-text-secondary); }
.navigation-heading { margin: var(--bec-space-section) var(--bec-space-group) var(--bec-space-tight); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.navigation-empty { margin: var(--bec-space-group); }
.skip-link { position: absolute; transform: translateY(-200%); }
.skip-link:focus { z-index: 1; transform: none; padding: var(--bec-space-tight); background: var(--bec-color-surface); }
</style>
