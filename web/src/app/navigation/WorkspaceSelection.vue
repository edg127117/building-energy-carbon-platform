<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '@/modules/auth/public'
import { t } from '@/locales'
import { ElAlert, ElButton, ArrowRight } from '@/shared/ui'
import WorkspaceBrand from './WorkspaceBrand.vue'
import { workspaceIcons } from './icons'
import { authorizedPages, workspaces } from './catalog'
const session = useSession()
const router = useRouter()
const route = useRoute()
const failure = ref(false)
const logoutFailed = ref(false)
const busy = ref(false)
const available = computed(() => workspaces.filter(system => authorizedPages(session.menus).some(page => page.system === system.id)))
async function retry() {
  busy.value = true
  try { await session.refresh(); failure.value = false; await router.replace('/systems') }
  catch { failure.value = true } finally { busy.value = false }
}
async function logout() {
  try { await session.logout(); await router.replace('/login') } catch { logoutFailed.value = true }
}
</script>
<template>
  <div class="selection management-surface">
    <header><WorkspaceBrand /><div class="account"><span class="username" :title="session.user?.username">{{ session.user?.username }}</span><ElButton text @click="logout">{{ t('workspaces.logout') }}</ElButton></div></header>
    <main>
      <h1>{{ t('workspaces.select') }}</h1>
      <ElAlert v-if="logoutFailed" :title="t('workspaces.logoutFailed')" type="error" :closable="false" />
      <section v-if="route.query.error || failure || session.failed" class="access-error">
        <ElAlert :title="t('workspaces.accessFailed')" type="error" :closable="false" />
        <ElButton :loading="busy" @click="retry">{{ t('common.retry') }}</ElButton>
      </section>
      <ElAlert v-else-if="!available.length" :title="t('workspaces.noAccess')" type="info" :closable="false" />
      <nav v-else :aria-label="t('workspaces.select')">
        <RouterLink v-for="system in available" :key="system.id" :to="authorizedPages(session.menus).find(page => page.system === system.id)!.path" class="system-link">
          <component :is="workspaceIcons[system.id]" class="system-icon" aria-hidden="true" />
          <strong>{{ t(system.titleKey) }}</strong>
          <span class="enter">{{ t('workspaces.enter') }}<ArrowRight aria-hidden="true" /></span>
        </RouterLink>
      </nav>
    </main>
  </div>
</template>
<style scoped>
.selection { min-height: 100%; display: flex; flex-direction: column; background: var(--bec-management-background); }
header { flex-shrink: 0; height: var(--bec-workspace-header-height); padding: 0 var(--bec-space-page); display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-group); background: var(--bec-workspace-header-background); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); box-shadow: var(--bec-shadow-card); position: relative; z-index: var(--bec-layer-navigation); }
.account { display: flex; align-items: center; gap: var(--bec-space-group); min-width: 0; }
.username { max-width: var(--bec-workspace-user-width); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--bec-color-text-secondary); }
main { width: 100%; max-width: var(--bec-workspace-cards-width); margin: auto; padding: var(--bec-ref-space-48) var(--bec-space-page); }
h1 { text-align: center; font-size: var(--bec-font-size-system); margin: 0 0 var(--bec-ref-space-40); }
nav { display: flex; justify-content: center; gap: var(--bec-space-section); }
.system-link { flex: 1; max-width: var(--bec-workspace-card-width); min-width: 0; min-height: var(--bec-workspace-card-height); box-sizing: border-box; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: var(--bec-space-section); padding: var(--bec-space-section); text-align: center; text-decoration: none; color: var(--bec-color-text-primary); background: var(--bec-color-surface); border-radius: var(--bec-management-radius); box-shadow: var(--bec-shadow-card); }
.system-link:hover, .system-link:focus-visible { box-shadow: var(--bec-shadow-raised); }
.system-link:hover .enter, .system-link:focus-visible .enter { color: var(--bec-color-action-hover); text-decoration: underline; text-underline-offset: var(--bec-ref-space-4); }
.system-icon { width: var(--bec-icon-large); height: var(--bec-icon-large); color: var(--bec-color-action-primary); stroke-width: 1.5; }
.enter { display: flex; align-items: center; gap: var(--bec-space-tight); margin-top: var(--bec-space-tight); color: var(--bec-color-action-primary); }
.enter svg { width: var(--bec-icon-small); height: var(--bec-icon-small); }
strong { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-normal); }
.access-error { display: grid; gap: var(--bec-space-group); }
</style>
