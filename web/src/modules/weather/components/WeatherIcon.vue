<template>
  <span class="weather-symbol" aria-hidden="true">
    <template v-for="(part, index) in parts" :key="index">
      <ArrowRight v-if="index" class="transition" />
      <component :is="part.icon" class="condition-icon" :class="part.tone" />
    </template>
  </span>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, Sun, CloudSun, Cloud, CloudDrizzle, CloudRain, CloudRainWind, CloudSnow, CloudLightning, CloudFog, Snowflake, Wind, CircleHelp } from '@/shared/ui'
import w from '../locales/zh-CN'
const props = defineProps<{ weather?: string | null }>()
function symbol(text: string) {
  if (/雷/.test(text)) return { icon: CloudLightning, tone: 'rain' }
  if (/雨夹雪|冻雨|冻毛毛雨/.test(text)) return { icon: Snowflake, tone: 'rain' }
  if (/雪/.test(text)) return { icon: CloudSnow, tone: 'rain' }
  if (/暴雨|大雨|强阵雨/.test(text)) return { icon: CloudRainWind, tone: 'rain' }
  if (/小雨|毛毛雨/.test(text)) return { icon: CloudDrizzle, tone: 'rain' }
  if (/雨/.test(text)) return { icon: CloudRain, tone: 'rain' }
  if (/雾|霾|沙|尘/.test(text)) return { icon: CloudFog, tone: 'cloud' }
  if (/多云/.test(text)) return { icon: CloudSun, tone: 'sun' }
  if (/晴/.test(text)) return { icon: Sun, tone: 'sun' }
  if (/阴/.test(text)) return { icon: Cloud, tone: 'cloud' }
  if (/风/.test(text)) return { icon: Wind, tone: 'cloud' }
  return { icon: CircleHelp, tone: 'cloud' }
}
const parts = computed(() => (props.weather?.trim() || '').split(w.weatherTransition).map(symbol))
</script>
<style scoped>
.weather-symbol { display: inline-flex; align-items: center; justify-content: center; gap: var(--bec-space-tight); min-height: var(--bec-icon-large); }
.condition-icon { width: var(--bec-icon-medium); height: var(--bec-icon-medium); }
.transition { width: var(--bec-icon-small); height: var(--bec-icon-small); color: var(--bec-color-text-secondary); }
.sun { color: var(--bec-color-warning); }
.rain { color: var(--bec-color-brand-primary); }
.cloud { color: var(--bec-color-text-secondary); }
</style>
