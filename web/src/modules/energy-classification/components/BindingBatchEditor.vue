<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElForm, ElFormItem, ElInput, ElOption, ElSelect } from '@/shared/ui'
import { t } from '@/locales'
import type { BindingSemantics, ClassificationBinding, ClassificationObjectType, ReviewStatus } from '../models/classification'
import { createBindingBatch } from '../models/classification-editor'

interface SelectOption { value: string; label: string }

const props = defineProps<{
  disabled: boolean
  contextKey: string
  schemes: SelectOption[]
  nodes: (schemeCode: string) => SelectOption[]
  objects: (objectType: ClassificationObjectType) => SelectOption[]
  objectTypes: ClassificationObjectType[]
}>()
const emit = defineEmits<{ add: [bindings: ClassificationBinding[]]; update: [bindings: ClassificationBinding[]] }>()

const schemeCode = ref('')
const nodeCode = ref('')
const objectType = ref<ClassificationObjectType>('POINT')
const objectIds = ref<string[]>([])
const evidence = ref('')
const semantics = ref<BindingSemantics>('DESCRIPTION')
const status = ref<ReviewStatus>('PENDING_EXPERT')
const mode = ref<'ADD' | 'UPDATE'>('ADD')
const availableObjects = computed(() => props.objects(objectType.value))

watch(schemeCode, () => { nodeCode.value = '' })
watch(objectType, () => { objectIds.value = [] })
watch(() => props.contextKey, () => { schemeCode.value = ''; nodeCode.value = ''; objectIds.value = []; evidence.value = ''; semantics.value = 'DESCRIPTION'; status.value = 'PENDING_EXPERT'; mode.value = 'ADD' })
watch(() => props.schemes.map(item => item.value).join('\0'), () => {
  if (!props.schemes.some(item => item.value === schemeCode.value)) schemeCode.value = ''
})
watch(() => props.nodes(schemeCode.value).map(item => item.value).join('\0'), () => {
  if (!props.nodes(schemeCode.value).some(item => item.value === nodeCode.value)) nodeCode.value = ''
})
watch(() => availableObjects.value.map(item => item.value).join('\0'), () => {
  const valid = new Set(availableObjects.value.map(item => item.value))
  objectIds.value = objectIds.value.filter(id => valid.has(id))
})

function addBatch() {
  if (props.disabled || !schemeCode.value || !nodeCode.value || !objectIds.value.length || !evidence.value.trim() || evidence.value.length > 500) return
  if (!props.nodes(schemeCode.value).some(item => item.value === nodeCode.value) || objectIds.value.some(id => !availableObjects.value.some(item => item.value === id))) return
  const bindings = createBindingBatch({ schemeCode: schemeCode.value, nodeCode: nodeCode.value, objectType: objectType.value, objectIds: objectIds.value, evidence: evidence.value, semantics: semantics.value, status: status.value })
  if (mode.value === 'ADD') emit('add', bindings)
  else emit('update', bindings)
  objectIds.value = []
  evidence.value = ''
}
</script>

<template>
  <ElForm class="binding-batch" label-position="top" @submit.prevent="addBatch">
    <ElFormItem :label="t('energyClassification.binding.batchMode')"><ElSelect v-model="mode" :disabled="disabled"><ElOption value="ADD" :label="t('energyClassification.binding.modeAdd')" /><ElOption value="UPDATE" :label="t('energyClassification.binding.modeUpdate')" /></ElSelect></ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.schemeCode')">
      <ElSelect v-model="schemeCode" filterable :disabled="disabled || !schemes.length" :placeholder="t('energyClassification.placeholders.select')">
        <ElOption v-for="item in schemes" :key="item.value" :value="item.value" :label="item.label" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.nodeCode')">
      <ElSelect v-model="nodeCode" filterable :disabled="disabled || !nodes(schemeCode).length" :placeholder="t('energyClassification.placeholders.select')">
        <ElOption v-for="item in nodes(schemeCode)" :key="item.value" :value="item.value" :label="item.label" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.objectType')">
      <ElSelect v-model="objectType" :disabled="disabled">
        <ElOption v-for="item in objectTypes" :key="item" :value="item" :label="t(`energyClassification.objectType.${item}`)" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.objectId')">
      <ElSelect v-model="objectIds" multiple filterable :multiple-limit="100" :disabled="disabled || !availableObjects.length" :placeholder="t('energyClassification.binding.batchSelect')">
        <ElOption v-for="item in availableObjects" :key="item.value" :value="item.value" :label="item.label" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.semantics')">
      <ElSelect v-model="semantics" :disabled="disabled"><ElOption v-for="item in (['DESCRIPTION', 'METERING'] as const)" :key="item" :value="item" :label="t(`energyClassification.semantics.${item}`)" /></ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.status')">
      <ElSelect v-model="status" :disabled="disabled"><ElOption v-for="item in (['PENDING_EXPERT', 'CONFIRMED'] as const)" :key="item" :value="item" :label="t(`energyClassification.status.${item}`)" /></ElSelect>
    </ElFormItem>
    <ElFormItem :label="t('energyClassification.fields.evidence')" required>
      <ElInput v-model="evidence" maxlength="500" show-word-limit :disabled="disabled" :placeholder="t('energyClassification.binding.batchEvidence')" />
    </ElFormItem>
    <div class="binding-batch-action">
      <span>{{ t('energyClassification.binding.batchHint') }}</span>
      <ElButton type="primary" :disabled="disabled || !schemeCode || !nodeCode || !objectIds.length || !evidence.trim() || evidence.length > 500" @click="addBatch">{{ t(mode === 'ADD' ? 'energyClassification.binding.batchAdd' : 'energyClassification.binding.batchUpdate', { count: objectIds.length }) }}</ElButton>
    </div>
  </ElForm>
</template>

<style scoped>
.binding-batch { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr)); gap: var(--bec-space-group); margin-block: var(--bec-space-section); }
.binding-batch-action { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; grid-column: 1 / -1; }
.binding-batch-action span { color: var(--bec-color-text-secondary); }
</style>
