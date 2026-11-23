<script setup lang="ts">
import { computed } from 'vue'
import type { ComfortAirflowSpeed, DoorVisualState } from '../api/comfort'
import copy from '../locales/zh-CN'

const props = defineProps<{
  label: string; status: string; occupancy: number | null; door: DoorVisualState
  acRunning: boolean; airflowSpeed: ComfortAirflowSpeed | null; pageVisible: boolean
}>()
const slots = [[142, 134], [202, 114], [190, 158], [244, 140]]
const people = computed(() => slots.slice(0, props.occupancy ?? 0))
const airflowCounts = { low: 2, middleLow: 2, middle: 3, middleHigh: 4, high: 4 }
const airflow = computed(() => {
  const count = props.acRunning && props.airflowSpeed ? airflowCounts[props.airflowSpeed] : 0
  return Array.from({ length: count }, (_, index) => {
    const dx = -18 + index * (count > 1 ? 36 / (count - 1) : 0)
    return `M ${188 + dx} 78 L ${206 + dx * 1.15} 114`
  })
})
</script>

<template>
  <svg
    class="comfort-room-scene" viewBox="10 10 350 240" role="img"
    :aria-label="[label, status].filter(Boolean).join(' · ')" :data-door-state="door"
    :class="{ 'room-airflow-paused': !pageVisible }" :data-ac-running="acRunning"
  >
    <polygon class="room-floor" points="190,94 334,166 190,238 46,166" />
    <path class="room-grid" d="M82 148l144 72m-108-90 144 72m-108-90 144 72M82 184l144-72m-108 90 144-72m-108 90 144-72" />
    <polygon class="room-wall" points="46,166 190,94 190,24 46,96" />
    <polygon class="room-wall room-wall-far" points="190,94 334,166 334,96 190,24" />
    <polygon class="room-window" points="74,126 116,105 116,74 74,95" />
    <polygon class="room-window" points="128,99 166,80 166,49 128,68" />
    <g class="room-fixtures">
      <polygon points="116,138 148,122 170,133 138,149" />
      <polygon points="178,116 210,100 232,111 200,127" />
      <polygon points="164,164 196,148 218,159 186,175" />
      <polygon class="room-unit-face" points="194,46 246,72 246,84 194,58" />
      <polygon class="room-unit-top" points="194,46 202,42 254,68 246,72" />
      <circle class="room-unit-indicator" cx="240" cy="74" r="2.6" />
    </g>
    <path v-for="segment in airflow" :key="segment" class="room-airflow-stream" :d="segment" aria-hidden="true" />
    <g v-for="([x, y], index) in people" :key="index" class="room-person" :transform="`translate(${x},${y})`" aria-hidden="true">
      <ellipse cx="0" cy="14" rx="11.5" ry="5.5" />
      <path d="M-6.5 12L-5 -1Q0 -4.5 5 -1L6.5 12Z" />
      <circle cx="0" cy="-7.8" r="4.8" />
    </g>
    <g v-if="occupancy === 0" class="room-empty-badge" transform="translate(185,138)">
      <rect x="-32" y="-11" width="64" height="22" rx="11" />
      <text x="0" y="3.5" text-anchor="middle">{{ copy.nobody }}</text>
    </g>
    <polygon class="room-door-frame" points="272,198 310,179 310,121 272,140" />
    <path v-if="door === 'open'" class="room-door-swing" d="M304 182Q268 188 238 177" stroke-dasharray="3 3" />
    <path
      class="room-door" :d="door === 'open' ? 'M272 198L236 177V123L272 144Z' : 'M272 198L306 179V125L272 144Z'"
      :stroke-dasharray="door === 'unknown' ? '4 3' : undefined"
    />
    <rect class="room-sensor" x="267" y="152" width="6" height="9" rx="1.5" />
  </svg>
</template>

<style scoped>
.comfort-room-scene { width: 100%; height: 100%; min-height: 0; display: block; --bec-room-door-color: var(--bec-ref-disabled); }
.comfort-room-scene[data-door-state='open'] { --bec-room-door-color: var(--bec-color-warning); }
.comfort-room-scene[data-door-state='closed'] { --bec-room-door-color: var(--bec-color-success); }
.room-floor { fill: var(--bec-color-surface); stroke: var(--bec-ref-comfort-scene-outline); stroke-width: 1.4; }
.room-grid { stroke: var(--bec-color-divider); fill: none; }
.room-wall { fill: var(--bec-ref-workspace-bg); stroke: var(--bec-ref-comfort-scene-outline); stroke-width: 1.2; }
.room-wall-far { fill: var(--bec-ref-comfort-scene-far-wall); }
.room-window {
  fill: var(--bec-ref-comfort-scene-window);
  stroke: var(--bec-ref-comfort-scene-window-outline);
  stroke-width: 1.2;
}
.room-fixtures { fill: var(--bec-ref-comfort-scene-fixture); stroke: var(--bec-ref-comfort-scene-fixture-outline); }
.room-unit-face { stroke-width: 1.4; }
.room-unit-top { fill: var(--bec-color-surface-secondary); stroke-width: 1.1; }
.comfort-room-scene[data-ac-running='true'] .room-unit-face {
  fill: var(--bec-color-surface);
  stroke: var(--bec-color-brand-primary);
}
.comfort-room-scene[data-ac-running='true'] .room-unit-top { stroke: var(--bec-color-brand-primary); }
.room-unit-indicator { fill: var(--bec-ref-disabled); stroke: none; }
.comfort-room-scene[data-ac-running='true'] .room-unit-indicator { fill: var(--bec-color-success); }
.room-airflow-stream {
  fill: none;
  stroke: var(--bec-color-brand-primary);
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-dasharray: 6 6;
  animation: room-airflow 1.6s linear infinite;
}
.room-airflow-paused .room-airflow-stream { animation-play-state: paused; }
@keyframes room-airflow {
  0%, 100% { stroke-dashoffset: 0; opacity: .9; }
  50% { stroke-dashoffset: -12; opacity: .45; }
}
@media (prefers-reduced-motion: reduce) {
  .room-airflow-stream { animation: none; }
}
.room-sensor { fill: var(--bec-room-door-color); }
.room-door-frame { fill: none; stroke: var(--bec-ref-comfort-scene-outline); stroke-width: 1.4; }
.room-door { fill: var(--bec-room-door-color); fill-opacity: .78; stroke: var(--bec-room-door-color); stroke-width: 1.5; }
.room-door-swing { fill: none; stroke: var(--bec-room-door-color); stroke-width: 1.2; }
.room-person { fill: var(--bec-color-brand-primary); }
.room-person ellipse { fill-opacity: .18; stroke: var(--bec-color-brand-primary); }
.room-person circle { fill: var(--bec-ref-comfort-scene-person-head); stroke: var(--bec-color-brand-primary); stroke-width: 1.2; }
.room-empty-badge rect { fill: var(--bec-color-surface); stroke: var(--bec-color-border); }
.room-empty-badge text { fill: var(--bec-color-text-secondary); font-size: var(--bec-ref-font-10); }
</style>
