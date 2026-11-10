<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElSelect, ElOption, ElInputNumber, Plus, Trash2, Settings2 } from '@/shared/ui'
import { t } from '@/locales'
import type { AlarmCondition, AlarmConditionNode, AlarmPointOption, ConditionOperator } from '../models/alarms'
const props = withDefaults(defineProps<{ modelValue: AlarmConditionNode; points: AlarmPointOption[]; depth?: number; readonly?: boolean }>(), { depth: 1, readonly: false })
const emit = defineEmits<{ 'update:modelValue': [value: AlarmConditionNode]; remove: []; stateSettings: [condition: AlarmCondition] }>()
const text = (key: string) => t('alarms.' + key)
const leaf = computed(() => props.modelValue.condition)
const point = computed(() => props.points.find(item => item.value === leaf.value?.pointId))
const operators: ConditionOperator[] = ['GT','GE','LT','LE','EQ','NE','BETWEEN','OUTSIDE']
function updateLeaf(fields: Partial<AlarmCondition>) {
  if (!leaf.value) return
  emit('update:modelValue', { condition: { ...leaf.value, ...fields } })
}
function choosePoint(id: string) {
  const selected = props.points.find(item => item.value === id)
  updateLeaf({ pointId: id, operator: selected?.dataType === 'DIGITAL' ? 'EQ' : 'GT', value: null, upperValue: null,
    states: selected?.states ?? [], dictionaryId: selected?.dictionaryId, dictionaryRevision: selected?.dictionaryRevision })
}
function replaceChild(index: number, value: AlarmConditionNode) {
  const children = [...(props.modelValue.children ?? [])]
  children[index] = value
  emit('update:modelValue', { ...props.modelValue, children })
}
function removeChild(index: number) {
  emit('update:modelValue', { ...props.modelValue, children: props.modelValue.children?.filter((_, at) => at !== index) ?? [] })
}
function add(group: boolean) {
  const node: AlarmConditionNode = group ? { logic: 'ALL', children: [{ condition: { pointId: '', operator: 'GT', value: null } }] }
    : { condition: { pointId: '', operator: 'GT', value: null } }
  emit('update:modelValue', { ...props.modelValue, children: [...(props.modelValue.children ?? []), node] })
}
</script>
<template>
  <div v-if="leaf" class="tree-leaf" :class="{ digital: point?.dataType === 'DIGITAL' }">
    <ElSelect :model-value="leaf.pointId" :aria-label="text('selectPoint')" :placeholder="text('selectPoint')" filterable :disabled="readonly" @change="choosePoint">
      <ElOption v-for="item in points" :key="item.value" :label="item.label" :value="item.value" />
    </ElSelect>
    <ElSelect :model-value="leaf.operator" :aria-label="text('comparisonAction')" :disabled="readonly" @change="updateLeaf({ operator: $event, upperValue: ['BETWEEN','OUTSIDE'].includes($event) ? leaf.upperValue : null })">
      <ElOption v-for="operator in point?.dataType === 'DIGITAL' ? (['EQ','NE'] as const) : operators" :key="operator" :label="text(operator)" :value="operator" />
    </ElSelect>
    <div v-if="point?.dataType === 'DIGITAL'" class="state-choice">
      <ElSelect :model-value="leaf.value" :placeholder="text('chooseState')" :aria-label="text('chooseState')" :disabled="readonly" @change="updateLeaf({ value: $event })">
        <ElOption v-for="state in leaf.states ?? []" :key="state.value" :label="state.label" :value="state.value" />
      </ElSelect>
      <ElButton text :aria-label="text('stateChoices')" :disabled="readonly" @click="emit('stateSettings', leaf)"><Settings2 /></ElButton>
    </div>
    <div v-else class="tree-number">
      <ElInputNumber :model-value="leaf.value" :controls="false" :aria-label="text('lowerValue')" :disabled="readonly" @update:model-value="updateLeaf({ value: $event ?? null })" />
      <ElInputNumber v-if="['BETWEEN','OUTSIDE'].includes(leaf.operator)" :model-value="leaf.upperValue" :controls="false" :aria-label="text('upperValue')" :disabled="readonly" @update:model-value="updateLeaf({ upperValue: $event ?? null })" />
      <span>{{ point?.unit }}</span>
    </div>
    <ElButton text type="danger" :aria-label="text('removeCondition')" :disabled="readonly" @click="emit('remove')"><Trash2 /></ElButton>
  </div>
  <div v-else class="tree-group">
    <div class="tree-group-head">
      <ElSelect :model-value="modelValue.logic ?? 'ALL'" :aria-label="text('conditionLogic')" :disabled="readonly" @change="emit('update:modelValue', { ...modelValue, logic: $event })"><ElOption value="ALL" :label="text('ALL')" /><ElOption value="ANY" :label="text('ANY')" /></ElSelect>
      <ElButton v-if="depth > 1" text type="danger" :aria-label="text('removeGroup')" :disabled="readonly" @click="emit('remove')"><Trash2 /></ElButton>
    </div>
    <AlarmConditionTreeEditor v-for="(child,index) in modelValue.children ?? []" :key="index" :model-value="child" :points="points" :depth="depth + 1" :readonly="readonly" @update:model-value="replaceChild(index,$event)" @remove="removeChild(index)" @state-settings="emit('stateSettings',$event)" />
    <div class="tree-add"><ElButton text type="primary" :disabled="readonly || (modelValue.children?.length ?? 0) >= 20" @click="add(false)"><Plus />{{ text('addCondition') }}</ElButton><ElButton v-if="depth < 2" text type="primary" :disabled="readonly" @click="add(true)">{{ text('addGroup') }}</ElButton></div>
  </div>
</template>
<style scoped>
.tree-group { display:grid; gap:var(--bec-space-tight); padding:var(--bec-space-tight); border:var(--bec-border-width) solid var(--bec-color-divider); border-radius:var(--bec-radius-control); background:var(--bec-color-surface) }
.tree-group .tree-group { border-left:var(--bec-ref-space-4) solid var(--bec-color-action-primary) }
.tree-group-head,.tree-add { display:flex; align-items:center; gap:var(--bec-space-tight); flex-wrap:wrap }
.tree-group-head .el-select { flex:1 }
.tree-leaf { display:grid; grid-template-columns:minmax(0,1.3fr) minmax(0,.85fr) minmax(0,1fr) auto; gap:var(--bec-ref-space-4); align-items:center }
.tree-leaf > * { min-width:0 }
.tree-leaf .el-select { width:100%; min-width:0 }
.tree-number,.state-choice { display:flex; gap:var(--bec-ref-space-4); align-items:center; min-width:0 }
.tree-number :deep(.el-input-number) { width:100%; min-width:0 }
.tree-number span { color:var(--bec-color-text-secondary); font-size:var(--bec-ref-font-12) }
.tree-leaf .el-button { padding:0; width:var(--bec-ref-space-24) }
svg { width:var(--bec-ref-space-16); height:var(--bec-ref-space-16) }
</style>
