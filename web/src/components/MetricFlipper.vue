<template>
  <div class="flex items-end justify-between gap-3">
    <div class="min-w-0">
      <div class="text-[11px] tracking-[0.18em] text-zinc-400/90">{{ label }}</div>
      <div class="mt-1 flex items-baseline gap-2">
        <div class="font-[650] leading-none text-zinc-50" :class="valueClass">
          <span class="inline-block transition-all duration-300" :style="{ transform: `translateY(${bump}px)` }">
            {{ displayValue }}
          </span>
        </div>
        <div v-if="unit" class="text-xs text-zinc-400">{{ unit }}</div>
      </div>
      <div v-if="hint" class="mt-2 text-xs text-zinc-500/90">{{ hint }}</div>
    </div>
    <div class="h-10 w-10 rounded-xl border border-white/10 bg-white/5 shadow-inner" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  label: string
  value: string | number | null | undefined
  unit?: string
  hint?: string
  tone?: 'info' | 'success' | 'danger' | 'neutral'
}>()

const bump = ref(0)

watch(
  () => props.value,
  () => {
    bump.value = -6
    window.setTimeout(() => {
      bump.value = 0
    }, 180)
  },
)

const displayValue = computed(() => {
  if (props.value === null || props.value === undefined) return '--'
  if (typeof props.value === 'number') return props.value.toFixed(2)
  return String(props.value)
})

const valueClass = computed(() => {
  if (props.tone === 'success') return 'text-[26px] text-emerald-300'
  if (props.tone === 'danger') return 'text-[26px] text-rose-300'
  if (props.tone === 'info') return 'text-[26px] text-sky-300'
  return 'text-[26px]'
})
</script>

