<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '@/modules/auth/public'
import { t } from '@/locales'
import { ElAlert, ElButton, ArrowRight } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
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
  <div class="selection">
    <header><BrandIdentity /><ElButton @click="logout">{{ t('workspaces.logout') }}</ElButton></header>
    <main>
      <h1>{{ t('workspaces.select') }}</h1>
      <p>{{ t('workspaces.selectHint') }}</p>
      <ElAlert v-if="logoutFailed" :title="t('workspaces.logoutFailed')" type="error" :closable="false" />
      <section v-if="route.query.error || failure || session.failed" class="access-error">
        <ElAlert :title="t('workspaces.accessFailed')" type="error" :closable="false" />
        <ElButton :loading="busy" @click="retry">{{ t('common.retry') }}</ElButton>
      </section>
      <ElAlert v-else-if="!available.length" :title="t('workspaces.noAccess')" type="info" :closable="false" />
      <nav v-else :aria-label="t('workspaces.select')">
        <RouterLink v-for="system in available" :key="system.id" :to="authorizedPages(session.menus).find(page => page.system === system.id)!.path" class="system-link">
          <span><strong>{{ t(system.titleKey) }}</strong><small>{{ t(system.hintKey) }}</small></span><ArrowRight aria-hidden="true" />
        </RouterLink>
      </nav>
    </main>
  </div>
</template>
<style scoped>
.selection { min-height: 100%; padding: var(--bec-space-page); }
header { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-group); }
main { max-width: var(--bec-selection-width); margin: var(--bec-selection-offset) auto; }
h1 { font-size: var(--bec-font-size-system); margin: 0; }
p { color: var(--bec-color-text-secondary); margin: var(--bec-space-tight) 0 var(--bec-space-section); }
nav { background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.system-link { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-section); padding: var(--bec-space-section); text-decoration: none; color: var(--bec-color-text-primary); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
.system-link:last-child { border-bottom: 0; }
.system-link:hover { background: var(--bec-color-surface-secondary); color: var(--bec-color-action-primary); }
strong { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
small { display: block; margin-top: var(--bec-space-tight); font-size: var(--bec-font-size-body); color: var(--bec-color-text-secondary); }
.access-error { display: grid; gap: var(--bec-space-group); }
</style>
