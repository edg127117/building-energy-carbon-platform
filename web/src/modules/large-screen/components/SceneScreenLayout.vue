<script setup lang="ts">
import { ref, useId } from 'vue'
import { ElButton, ArrowLeft, ArrowRight } from '@/shared/ui'
import { t } from '@/locales'
const leftOpen = ref(true)
const rightOpen = ref(true)
const id = useId()
// 场景尺寸与信息面板状态无关；只有实际面板和按钮接收指针，中央空白不会阻断三维交互。
</script>
<template>
  <section class="scene-layout" :class="{ 'has-summary': !!$slots.top }">
    <div class="scene-layer" :aria-label="t('largeScreen.scene')"><slot name="scene" /></div>
    <div class="information-layer">
      <section v-if="$slots.top" class="scene-summary surface" :aria-label="t('largeScreen.summary')"><slot name="top" /></section>
      <div v-if="$slots.left" class="side-anchor left-anchor" :class="{ collapsed: !leftOpen }">
        <aside v-show="leftOpen" :id="id + '-left'" class="side-panel surface" :aria-label="t('largeScreen.leftPanel')"><slot name="left" /></aside>
        <ElButton class="panel-toggle" :icon="leftOpen ? ArrowLeft : ArrowRight" :aria-expanded="leftOpen" :aria-controls="id + '-left'" :aria-label="t(leftOpen ? 'largeScreen.collapseLeft' : 'largeScreen.expandLeft')" :title="t(leftOpen ? 'largeScreen.collapseLeft' : 'largeScreen.expandLeft')" @click="leftOpen = !leftOpen" />
      </div>
      <div v-if="$slots.right" class="side-anchor right-anchor" :class="{ collapsed: !rightOpen }">
        <aside v-show="rightOpen" :id="id + '-right'" class="side-panel surface" :aria-label="t('largeScreen.rightPanel')"><slot name="right" /></aside>
        <ElButton class="panel-toggle" :icon="rightOpen ? ArrowRight : ArrowLeft" :aria-expanded="rightOpen" :aria-controls="id + '-right'" :aria-label="t(rightOpen ? 'largeScreen.collapseRight' : 'largeScreen.expandRight')" :title="t(rightOpen ? 'largeScreen.collapseRight' : 'largeScreen.expandRight')" @click="rightOpen = !rightOpen" />
      </div>
    </div>
  </section>
</template>
<style scoped>
.scene-layout { position: relative; width: 100%; height: 100%; min-height: 0; isolation: isolate; --bec-scene-side-top: var(--bec-monitor-padding); }
.has-summary { --bec-scene-side-top: calc(var(--bec-monitor-padding) + var(--bec-scene-summary-height) + var(--bec-monitor-gap)); }
.scene-layer { position: absolute; inset: 0; z-index: var(--bec-layer-scene); background: var(--bec-color-scene-background); }
.information-layer { position: absolute; inset: 0; z-index: var(--bec-layer-panels); pointer-events: none; }
.surface { pointer-events: auto; background: var(--bec-color-scene-panel); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-dialog); padding: var(--bec-monitor-padding); min-width: 0; min-height: 0; }
.scene-summary { position: absolute; top: var(--bec-monitor-padding); left: var(--bec-monitor-padding); right: var(--bec-monitor-padding); height: var(--bec-scene-summary-height); }
.side-anchor { position: absolute; top: var(--bec-scene-side-top); bottom: var(--bec-monitor-padding); width: var(--bec-scene-panel-width); }
.left-anchor { left: var(--bec-monitor-padding); }
.right-anchor { right: var(--bec-monitor-padding); }
.side-anchor.collapsed { width: 0; }
.side-panel { height: 100%; }
.panel-toggle { position: absolute; top: 50%; transform: translateY(-50%); width: var(--bec-scene-toggle-width); height: var(--bec-scene-toggle-height); padding: 0; pointer-events: auto; }
.left-anchor .panel-toggle { left: 100%; }
.right-anchor .panel-toggle { right: 100%; }
</style>
