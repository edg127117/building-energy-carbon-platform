<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElPopover, Clock, ArrowRight } from '@/shared/ui'
import { t } from '@/locales'
import { currentBeijingDate, validScreenPeriod, type ScreenPeriod } from '../models/screen-period'
const model = defineModel<ScreenPeriod>({ required: true })
const props = defineProps<{ label: string; allowDay?: boolean }>()
const open = ref(false), draft = ref('')
const kind = ref<ScreenPeriod['kind']>('year')
const valid = computed(() => validScreenPeriod({ kind: kind.value, value: draft.value }))
function begin() { kind.value = props.allowDay ? model.value.kind : model.value.kind === 'day' ? 'month' : model.value.kind; draft.value = model.value.value }
function updateDraft(event: Event) { draft.value = (event.target as HTMLInputElement).value }
function changeKind(next: ScreenPeriod['kind']) {
  if (next === kind.value) return
  kind.value = next
  draft.value = next === 'year' ? draft.value.slice(0, 4) : next === 'month' ? draft.value.slice(0, 4) + '-01' : currentBeijingDate()
}
function apply() { if (valid.value) { model.value = { kind: kind.value, value: draft.value }; open.value = false } }
</script>
<template>
  <ElPopover v-model:visible="open" trigger="click" placement="bottom-end" :teleported="false" width="var(--bec-screen-popover-width)" @show="begin">
    <template #reference><button class="period-trigger" :aria-label="label"><Clock :size="13" /><span>{{ t(model.kind === 'year' ? 'largeScreen.yearShort' : model.kind === 'month' ? 'largeScreen.monthShort' : 'largeScreen.dayShort') }}{{ ' · ' }}{{ model.value }}</span><ArrowRight :size="12" /></button></template>
    <form class="period-editor" @submit.prevent="apply">
      <div class="period-kinds" role="group" :aria-label="t('largeScreen.periodType')"><button v-for="item in (props.allowDay ? ['year', 'month', 'day'] as const : ['year', 'month'] as const)" :key="item" type="button" :aria-pressed="kind === item" @click="changeKind(item)">{{ t(item === 'year' ? 'largeScreen.yearShort' : item === 'month' ? 'largeScreen.monthShort' : 'largeScreen.dayShort') }}</button></div>
      <label>{{ label }}<input :key="kind" :value="draft" :type="kind === 'year' ? 'number' : kind === 'month' ? 'month' : 'date'" :min="kind === 'year' ? '2000' : kind === 'month' ? '2000-01' : '2000-01-01'" :max="kind === 'year' ? '2200' : kind === 'month' ? '2200-12' : '2200-12-31'" required :aria-label="label" @input="updateDraft" /></label>
      <div class="period-actions"><button type="button" @click="open = false">{{ t('largeScreen.cancel') }}</button><button type="submit" :disabled="!valid">{{ t('largeScreen.apply') }}</button></div>
    </form>
  </ElPopover>
</template>

<style scoped src="./screen-period-control.css"></style>
