<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElPopover, Clock, ArrowRight } from '@/shared/ui'
import { t } from '@/locales'
import { validScreenPeriod, type ScreenPeriod } from '../models/screen-period'
const model = defineModel<ScreenPeriod>({ required: true })
defineProps<{ label: string }>()
const open = ref(false), draft = ref('')
const kind = ref<ScreenPeriod['kind']>('year')
const valid = computed(() => validScreenPeriod({ kind: kind.value, value: draft.value }))
function begin() { kind.value = model.value.kind; draft.value = model.value.value }
function updateDraft(event: Event) { draft.value = (event.target as HTMLInputElement).value }
function changeKind(next: ScreenPeriod['kind']) {
  if (next === kind.value) return
  kind.value = next
  draft.value = next === 'year' ? draft.value.slice(0, 4) : draft.value.slice(0, 4) + '-01'
}
function apply() { if (valid.value) { model.value = { kind: kind.value, value: draft.value }; open.value = false } }
</script>
<template>
  <ElPopover v-model:visible="open" trigger="click" placement="bottom-end" :teleported="false" width="var(--bec-screen-popover-width)" @show="begin">
    <template #reference><button class="period-trigger" :aria-label="label"><Clock :size="13" /><span>{{ t(model.kind === 'year' ? 'largeScreen.yearShort' : 'largeScreen.monthShort') }}{{ ' · ' }}{{ model.value }}</span><ArrowRight :size="12" /></button></template>
    <form class="period-editor" @submit.prevent="apply">
      <div class="period-kinds" role="group" :aria-label="t('largeScreen.periodType')"><button v-for="item in (['year', 'month'] as const)" :key="item" type="button" :aria-pressed="kind === item" @click="changeKind(item)">{{ t(item === 'year' ? 'largeScreen.yearShort' : 'largeScreen.monthShort') }}</button></div>
      <label>{{ label }}<input :key="kind" :value="draft" :type="kind === 'year' ? 'number' : 'month'" :min="kind === 'year' ? '2000' : '2000-01'" :max="kind === 'year' ? '2200' : '2200-12'" required :aria-label="label" @input="updateDraft" /></label>
      <div class="period-actions"><button type="button" @click="open = false">{{ t('largeScreen.cancel') }}</button><button type="submit" :disabled="!valid">{{ t('largeScreen.apply') }}</button></div>
    </form>
  </ElPopover>
</template>

<style scoped src="./screen-period-control.css"></style>
