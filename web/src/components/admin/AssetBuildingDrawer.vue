<template>
  <a-drawer :open="open" :title="building ? '编辑建筑' : '新建建筑'" width="480" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="建筑名称" required><a-input v-model:value="form.buildingName" maxlength="100" /></a-form-item>
      <a-form-item label="建筑编码"><a-input v-model:value="form.buildingCode" maxlength="64" /></a-form-item>
      <div class="asset-form-grid"><a-form-item label="建筑类型"><a-input v-model:value="form.buildingType" maxlength="64" /></a-form-item><a-form-item label="气候区"><a-input v-model:value="form.climateZone" maxlength="64" /></a-form-item></div>
      <div class="asset-form-grid"><a-form-item label="建成年份"><a-input-number v-model:value="form.constructionYear" :min="1800" :max="2200" style="width:100%" /></a-form-item><a-form-item label="总建筑面积（㎡）"><a-input-number v-model:value="form.totalGfa" :min="0" style="width:100%" /></a-form-item></div>
      <a-form-item label="档案状态"><a-select v-model:value="form.status" :options="statusOptions" /></a-form-item>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存建筑</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { AssetBuildingForm, AssetBuildingView } from '@/types/assets'

/** 建筑表单只提交 V1 请求字段；引用计数和允许动作始终由后端返回。 */
const props = defineProps<{ open: boolean; building?: AssetBuildingView | null; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: AssetBuildingForm] }>()
const form = reactive({ buildingName: '', buildingCode: '', buildingType: '', climateZone: '', constructionYear: undefined as number | undefined, totalGfa: undefined as number | undefined, status: 'ACTIVE' })
const statusOptions = [{ label: '启用', value: 'ACTIVE' }]

watch(() => [props.open, props.building] as const, () => Object.assign(form, {
  buildingName: props.building?.buildingName ?? '', buildingCode: props.building?.buildingCode ?? '', buildingType: props.building?.buildingType ?? '',
  climateZone: props.building?.climateZone ?? '', constructionYear: props.building?.constructionYear ?? undefined, totalGfa: props.building?.totalGfa ?? undefined, status: props.building?.status ?? 'ACTIVE',
}), { immediate: true })

function submit() {
  if (!form.buildingName.trim()) return
  emit('save', {
    buildingName: form.buildingName.trim(), buildingCode: nullable(form.buildingCode), buildingType: nullable(form.buildingType),
    climateZone: nullable(form.climateZone), constructionYear: form.constructionYear ?? null, totalGfa: form.totalGfa ?? null, status: form.status,
  })
}
function nullable(value: string) { return value.trim() || null }
</script>

<style scoped>.asset-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }</style>
