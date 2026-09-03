<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElAlert, ElButton, Monitor } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import ScreenNavigation from '@/app/layouts/monitor/ScreenNavigation.vue'
import { useShellRuntime } from '@/app/providers/runtime'
import { useShellStore } from '@/app/providers/shell-store'
import { t } from '@/locales'
useShellRuntime()
const shell = useShellStore()
const route = useRoute()
const navigationOpen = ref(false)
const content = ref<HTMLElement | null>(null)
</script>

<template>
  <div class="office-layout" data-page-mode="office">
    <a class="skip-link" href="#platform-content" @click.prevent="content?.focus()">{{ t('navigation.skipContent') }}</a>
    <header>
      <BrandIdentity />
      <ElButton :icon="Monitor" @click="navigationOpen = true">{{ t('navigation.switchScreen') }}</ElButton>
    </header>
    <main id="platform-content" ref="content" tabindex="-1" :aria-label="t('navigation.content')">
      <ElAlert v-if="shell.navigationFailed" :title="t('error.page')" type="error" show-icon :closable="false" />
      <ElAlert v-if="shell.offline" :title="t('error.offline')" type="warning" show-icon :closable="false" />
      <ElAlert :title="t('common.foundation')" :description="t('common.foundationDescription')" type="info" :closable="false" />
      <RouterView v-slot="{ Component }">
        <StateBoundary :key="route.path" :reset-key="route.path"><component :is="Component" /></StateBoundary>
      </RouterView>
    </main>
    <ScreenNavigation v-model="navigationOpen" />
  </div>
</template>

<style scoped>
.office-layout { min-height: 100%; background: var(--bec-color-page); }
header { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-header-padding) var(--bec-space-page); background: var(--bec-color-surface); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
main { display: grid; gap: var(--bec-space-section); padding: var(--bec-space-page); }
.skip-link { position: absolute; transform: translateY(-200%); }
.skip-link:focus { transform: none; padding: var(--bec-space-tight); background: var(--bec-color-surface); }
</style>
