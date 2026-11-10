<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RefreshCw } from '@/shared/ui'
import { t } from '@/locales'
import ScreenSummaryCandidate from '../../components/ScreenSummaryCandidate.vue'
import { prototypeAlarms, prototypeDeviceFrames } from '../../models/screen-prototype'

const props = defineProps<{ initialVariant?: 'a' | 'b' | 'c' }>()
const emit = defineEmits<{ variantChange: [value: 'a' | 'b' | 'c'] }>()
const variant = ref(props.initialVariant ?? 'a')
const frame = ref(0), updatedAt = ref(Date.now())
const devices = computed(() => prototypeDeviceFrames[frame.value])
const s = (key: string) => t('largeScreen.' + key)
const variants = ['a', 'b', 'c'] as const
const description = computed(() => s('summaryReview' + variant.value.toUpperCase() + 'Description'))
watch(variant, value => emit('variantChange', value))
// 仅评审页手动播放已批准的原型快照，候选组件始终只展示传入状态，不发出设备指令。
function refreshPreview() { frame.value = (frame.value + 1) % prototypeDeviceFrames.length; updatedAt.value = Date.now() }
</script>

<template>
  <main class="summary-review">
    <header class="review-heading"><h1>{{ s('summaryReviewTitle') }}</h1><p>{{ s('summaryReviewIntro') }}</p></header>
    <nav class="review-nav" :aria-label="s('summaryReviewVariants')">
      <button v-for="item in variants" :key="item" :aria-pressed="variant === item" @click="variant = item">{{ s('summaryReview' + item.toUpperCase()) }}</button>
      <button class="refresh-preview" @click="refreshPreview"><RefreshCw :size="15" />{{ s('summaryReviewRefresh') }}</button>
    </nav>
    <p class="review-description">{{ description }}</p>
    <section class="review-stage"><ScreenSummaryCandidate :variant="variant" :alarms="prototypeAlarms" :devices="devices" :updated-at="updatedAt" /></section>
  </main>
</template>

<style scoped>
.summary-review { min-height: 100%; box-sizing: border-box; padding: var(--bec-ref-space-32); color: var(--bec-screen-ink); background: var(--bec-ref-bg); font-family: var(--bec-ref-family-body); line-height: var(--bec-screen-line-height); }
.summary-review button { font-family: inherit; }
.review-heading, .review-nav, .review-description { max-width: var(--bec-summary-review-width); margin-inline: auto; }
.review-heading h1 { margin: 0; font-size: var(--bec-screen-font-display); font-weight: var(--bec-screen-weight); }
.review-heading p { margin: var(--bec-ref-space-8) 0 var(--bec-ref-space-24); color: var(--bec-screen-muted); font-size: var(--bec-screen-font-body); }
.review-nav { display: flex; flex-wrap: wrap; gap: var(--bec-ref-space-8); }
.review-nav button { display: inline-flex; align-items: center; gap: var(--bec-ref-space-6); padding: var(--bec-ref-space-8) var(--bec-ref-space-16); border: var(--bec-ref-border-width) solid var(--bec-screen-divider); border-radius: var(--bec-ref-radius-8); color: var(--bec-screen-muted); background: var(--bec-ref-white); font-size: var(--bec-screen-font-body); cursor: pointer; }
.review-nav button[aria-pressed=true] { color: var(--bec-ref-white); border-color: var(--bec-screen-blue); background: var(--bec-screen-blue); }
.review-nav .refresh-preview { margin-left: auto; }
.review-nav button:focus-visible { outline: var(--bec-ref-focus-width) solid var(--bec-screen-blue); outline-offset: var(--bec-ref-space-2); }
.review-description { margin-top: var(--bec-ref-space-16); margin-bottom: var(--bec-ref-space-20); font-size: var(--bec-screen-font-body); color: var(--bec-screen-muted); }
.review-stage { display: flex; justify-content: center; align-items: flex-start; padding: var(--bec-ref-space-32); background: var(--bec-screen-neutral); border-radius: var(--bec-ref-radius-12); }
@media (max-width: 760px) { .summary-review { padding: var(--bec-ref-space-16); } .review-stage { padding: var(--bec-ref-space-16); } .review-nav .refresh-preview { margin-left: 0; } }
</style>
