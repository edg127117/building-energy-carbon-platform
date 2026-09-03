<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElAlert, Monitor, Maximize, Minimize, ArrowLeft } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import StateBoundary from '@/shared/components/StateBoundary.vue'
import { useLogicalCanvas } from '@/shared/composables/useLogicalCanvas'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import ScreenNavigation from './ScreenNavigation.vue'
import { useFullscreen } from './useFullscreen'
import { useShellRuntime } from '@/app/providers/runtime'
import { useShellStore } from '@/app/providers/shell-store'
const { viewportRef, canvasStyle } = useLogicalCanvas()
const { active, failed, toggle } = useFullscreen()
const { now } = useShellRuntime()
const shell = useShellStore()
const route = useRoute()
const router = useRouter()
const navigationOpen = ref(false)
</script>

<template>
  <div ref="viewportRef" class="monitor-viewport">
    <div class="monitor-canvas" :style="canvasStyle" data-page-mode="monitor">
      <header class="monitor-header">
        <BrandIdentity />
        <h1>{{ t(String(route.meta.titleKey ?? 'navigation.switchScreen')) }}</h1>
        <div class="bec-actions">
          <ElButton :icon="Monitor" @click="navigationOpen = true">{{ t('navigation.switchScreen') }}</ElButton>
          <ElButton :icon="active ? Minimize : Maximize" @click="toggle">{{ t(active ? 'common.exitFullScreen' : 'common.fullScreen') }}</ElButton>
          <ElButton :icon="ArrowLeft" @click="router.push('/office')">{{ t('navigation.returnOffice') }}</ElButton>
        </div>
        <time class="monitor-clock" :datetime="now.toISOString()">{{ formatDateTime(now) }}</time>
      </header>
      <div class="monitor-notices">
        <ElAlert v-if="shell.navigationFailed" :title="t('error.page')" type="error" show-icon :closable="false" />
        <ElAlert v-if="shell.offline" :title="t('error.offline')" type="warning" show-icon :closable="false" />
        <ElAlert v-if="failed" :title="t('error.fullscreen')" type="error" show-icon :closable="false" />
        <ElAlert :title="t('common.foundation')" :description="t('common.foundationDescription')" type="info" :closable="false" />
      </div>
      <main id="platform-content" class="monitor-content" :aria-label="t('navigation.content')">
        <RouterView v-slot="{ Component }">
          <StateBoundary :key="route.path" :reset-key="route.path"><component :is="Component" /></StateBoundary>
        </RouterView>
      </main>
      <!-- 对话框保留在变换后的画布内，遮罩与弹层使用同一坐标系。 -->
      <ScreenNavigation v-model="navigationOpen" />
    </div>
  </div>
</template>

<style scoped>
.monitor-viewport { position: relative; width: 100%; height: 100%; overflow: hidden; background: var(--bec-color-page); }
.monitor-canvas { --bec-font-size-body: var(--bec-monitor-font-body); --bec-font-size-small: var(--bec-monitor-font-small); --bec-font-size-title: var(--bec-monitor-font-panel); --bec-chart-font-size: var(--bec-monitor-font-small); display: grid; grid-template-rows: auto auto minmax(0, 1fr); background: var(--bec-color-page); font-size: var(--bec-monitor-font-body); }
.monitor-header { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: var(--bec-monitor-gap); padding: var(--bec-monitor-padding) var(--bec-monitor-header-inline); background: var(--bec-color-surface); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
.monitor-header h1 { margin: 0; font-size: var(--bec-monitor-font-title); }
.bec-actions { justify-self: end; }
.monitor-clock { grid-column: 3; justify-self: end; font-family: var(--bec-font-family-number); font-size: var(--bec-monitor-font-small); font-variant-numeric: tabular-nums; }
.monitor-notices { padding: var(--bec-monitor-gap) var(--bec-monitor-padding) 0; display: grid; gap: var(--bec-monitor-gap); }
.monitor-content { min-width: 0; min-height: 0; padding: var(--bec-monitor-padding); }
</style>
