<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ElButton } from '@/shared/ui'
import type { DailyTrendDay, DailyTrends } from '../api/daily-trends'
import { dailyStaticSource, readDailyStaticEvidence } from '../api/daily-static-evidence'
import copy from '../locales/daily-static-evidence'

const props = defineProps<{ views: readonly DailyTrends[] }>()
const entries = computed(() => props.views.flatMap(view => view.days.flatMap(day => {
  const source = dailyStaticSource(day)
  return source ? [{ key: `${view.buildingId}:${day.date}:${source.grain}:${source.calculationBatchId}`, day, source, nature: view.resultNature }] : []
})))
const busy = ref(''), failed = ref(false)
let active: AbortController | undefined
function cancel() { active?.abort(); active = undefined; busy.value = ''; failed.value = false }
// Ignore unrelated parent renders; only scope or frozen evidence identity invalidates a pending download.
const identity = computed(() => JSON.stringify(props.views.map(view => [
  view.buildingId, view.month, view.sourceVersion, view.evidenceHash,
  view.days.map(day => [day.date, dailyStaticSource(day)]),
])))
watch(identity, cancel)
onScopeDispose(cancel)
async function download(key: string, day: DailyTrendDay) {
  cancel()
  const controller = new AbortController()
  active = controller; busy.value = key
  try {
    const evidence = await readDailyStaticEvidence(day, controller.signal)
    if (active !== controller || controller.signal.aborted) return
    const url = URL.createObjectURL(new Blob([evidence], { type: 'application/json' }))
    try {
      const anchor = document.createElement('a')
      anchor.href = url; anchor.download = `static-carbon-${day.date}-${dailyStaticSource(day)?.grain}.json`
      document.body.appendChild(anchor)
      try { anchor.click() } finally { anchor.remove() }
    } finally { URL.revokeObjectURL(url) }
  } catch {
    if (active === controller && !controller.signal.aborted) failed.value = true
  } finally {
    if (active === controller) { active = undefined; busy.value = '' }
  }
}
</script>

<template>
  <details v-if="entries.length" data-test="daily-static-evidence">
    <summary>{{ copy.title }}</summary>
    <p>{{ copy.rule }}</p>
    <p v-if="failed" role="alert">{{ copy.failed }}</p>
    <ul>
      <li v-for="entry in entries" :key="entry.key">
        {{ entry.day.date }}{{ copy.separator }}{{ entry.source.grain }}{{ copy.separator }}{{ entry.nature }}{{ copy.separator }}{{ entry.source.declaredSourceNature ?? copy.unknown }}
        <ElButton :loading="busy === entry.key" @click="download(entry.key, entry.day)">{{ copy.download }}</ElButton>
      </li>
    </ul>
  </details>
</template>
