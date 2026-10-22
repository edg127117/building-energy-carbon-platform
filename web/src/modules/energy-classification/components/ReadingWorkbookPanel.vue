<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElFormItem, ElInput, ElSpace } from '@/shared/ui'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import { classificationApi } from '../api/classification'
import { readingWorkbookApi, type WorkbookPreview } from '../api/reading-workbook'
import type { ReadingImportView } from '../models/classification'
import { workbookCopy as copy } from '../locales/reading-workbook.zh-CN'
const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ dirtyChange: [value: boolean]; imported: [batch: ReadingImportView] }>()
const file = ref<File | null>(null), fileInput = ref<HTMLInputElement | null>(null), fileRevision = ref(0), reason = ref(''), key = ref(crypto.randomUUID())
const result = ref<WorkbookPreview | null>(null), busy = ref(false), error = ref(''), notice = ref('')
let generation = 0, savedSignature = ''
const signature = computed(() => `${props.buildingId}/${file.value?.name}/${file.value?.size}/${file.value?.lastModified}/${fileRevision.value}/${reason.value}/${key.value}`)
const dirty = computed(() => !!file.value || !!reason.value || busy.value)
const canImport = computed(() => !!result.value?.request && result.value.preview.status === 'PREVIEW' && savedSignature === signature.value && !busy.value)
watch(dirty, value => emit('dirtyChange', value))
// 文件、原因或建筑一变，旧预检不能再导入；同步释放旧命令的 busy，避免其 finally 因代际失效而遗留遮罩。
watch(signature, () => { generation++; result.value = null; savedSignature = ''; busy.value = false })
watch(() => props.buildingId, clear)
function clear() { generation++; file.value = null; reason.value = ''; result.value = null; savedSignature = ''; key.value = crypto.randomUUID(); error.value = ''; notice.value = ''; busy.value = false; if (fileInput.value) fileInput.value.value = '' }
function select(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null; fileRevision.value++ }
async function template() {
  if (!props.buildingId || busy.value) return
  const owner = generation; busy.value = true; error.value = ''
  try {
    const blob = await readingWorkbookApi.template(props.buildingId)
    if (owner !== generation) return
    const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'manual-energy-readings.xlsx'; link.click(); URL.revokeObjectURL(url)
  } catch (failure) { if (owner === generation) error.value = requestErrorMessage(failure) }
  finally { if (owner === generation) busy.value = false }
}
async function preview() {
  if (!file.value || !reason.value.trim() || busy.value) { error.value = copy.noFile; return }
  const owner = generation, snapshot = signature.value; busy.value = true; error.value = ''; result.value = null
  try {
    const response = await readingWorkbookApi.preview(props.buildingId, file.value, key.value, reason.value.trim())
    if (owner !== generation || snapshot !== signature.value) return
    if (response.preview.status !== 'PREVIEW' || !response.request) {
      result.value = { request: null, preview: response.preview }
      savedSignature = ''
      return
    }
    result.value = response; savedSignature = snapshot
  } catch (failure) { if (owner === generation) error.value = requestErrorMessage(failure) }
  finally { if (owner === generation) busy.value = false }
}
async function submit() {
  if (!canImport.value || !result.value?.request) return
  const owner = generation; busy.value = true; error.value = ''
  try {
    const response = await classificationApi.importReadings(props.buildingId, result.value.request)
    if (owner !== generation) return
    if (response.status === 'INVALID') { result.value = { request: null, preview: response }; savedSignature = ''; return }
    clear(); notice.value = copy.imported; emit('imported', response)
  } catch (failure) {
    if (owner === generation) {
      if (requestErrorStatus(failure) === 409) { result.value = null; savedSignature = '' }
      error.value = requestErrorMessage(failure)
    }
  }
  finally { if (owner === generation) busy.value = false }
}
</script>
<template>
  <ElCard shadow="never" :header="copy.title">
    <ElAlert :title="copy.note" type="info" :closable="false" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" /><ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElButton :disabled="!buildingId || busy" @click="template">{{ copy.template }}</ElButton>
    <ElFormItem :label="copy.file"><input ref="fileInput" data-test="workbook-file" type="file" accept=".xlsx" :disabled="busy || !buildingId" @change="select"></ElFormItem>
    <ElFormItem :label="copy.reason"><ElInput v-model="reason" data-test="workbook-reason" :disabled="busy" maxlength="500" /></ElFormItem>
    <ElSpace wrap><ElButton data-test="workbook-preview" :disabled="!buildingId || busy || !file || !reason.trim()" @click="preview">{{ copy.preview }}</ElButton><ElButton data-test="workbook-import" type="primary" :disabled="!canImport" @click="submit">{{ copy.submit }}</ElButton><ElButton v-if="dirty" :disabled="busy" @click="clear">{{ copy.clear }}</ElButton></ElSpace>
    <template v-if="result"><p>{{ copy.result }}{{ copy.separator }}{{ result.preview.status }}{{ copy.separator }}{{ copy.count }} {{ result.request?.readings.length ?? 0 }}</p><p v-for="(issue, index) in result.preview.issues" :key="index">{{ issue.reference }}{{ copy.separator }}{{ issue.message }}</p></template>
  </ElCard>
</template>
