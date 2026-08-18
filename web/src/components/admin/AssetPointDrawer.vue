<template>
  <a-drawer :open="open" title="编辑测点" width="440" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="测点名称"><a-input v-model:value="form.pointName" maxlength="100" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="最小值"><a-input-number v-model:value="form.minValue" style="width:100%" /></a-form-item><a-form-item label="最大值"><a-input-number v-model:value="form.maxValue" style="width:100%" /></a-form-item></div>
      <div class="asset-form-grid"><a-form-item label="参与计算"><a-switch v-model:checked="form.forCalculation" /></a-form-item><a-form-item label="测点状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item></div>
      <a-alert v-if="point?.sourceAliases.length" type="info" show-icon message="来源别名由设备绑定管理" :description="point.sourceAliases.join('、')" />
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存测点</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { AssetDataPointUpdateRequest, AssetDataPointView } from '@/types/assets'

/** 测点编辑只覆盖范围、计算标记和状态，来源别名保留为只读绑定证据。 */
const props = defineProps<{ open: boolean; point?: AssetDataPointView | null; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: AssetDataPointUpdateRequest] }>()
const form = reactive({ pointName: '', minValue: undefined as number | undefined, maxValue: undefined as number | undefined, forCalculation: false, status: 'ACTIVE' })
const statusOptions = [{ label: '启用', value: 'ACTIVE' }]
watch(() => [props.open, props.point] as const, () => Object.assign(form, {
  pointName: props.point?.pointName ?? '', minValue: props.point?.minValue ?? undefined, maxValue: props.point?.maxValue ?? undefined,
  forCalculation: props.point?.forCalculation ?? false, status: props.point?.status ?? 'ACTIVE',
}), { immediate: true })
function submit() { emit('save', { pointName: form.pointName.trim() || undefined, minValue: form.minValue ?? null, maxValue: form.maxValue ?? null, forCalculation: form.forCalculation, status: form.status }) }
</script>

<style scoped>.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }</style>
