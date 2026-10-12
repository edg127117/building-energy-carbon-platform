<template>
  <ElDialog :model-value="open" :title="binding ? w.edit : w.create" width="min(92%, 600px)" :close-on-click-modal="false" :close-on-press-escape="!command.pending.value" :show-close="!command.pending.value" @update:model-value="emit('update:open', $event)">
    <ElAlert :title="w.coordinateHint" type="info" :closable="false" />
    <ElForm label-position="top" :disabled="command.pending.value" @submit.prevent="save">
      <ElFormItem :label="w.name"><ElInput v-model="form.name" maxlength="100" /></ElFormItem>
      <div class="coordinates"><ElFormItem :label="w.latitude"><ElInputNumber v-model="form.latitude" :min="-90" :max="90" :precision="6" /></ElFormItem><ElFormItem :label="w.longitude"><ElInputNumber v-model="form.longitude" :min="-180" :max="180" :precision="6" /></ElFormItem></div>
      <ElFormItem :label="w.cityCode"><ElInput v-model="form.cityCode" maxlength="9" /></ElFormItem>
      <ElFormItem :label="w.coverage"><ElSelect v-model="form.coverage"><ElOption v-for="value in ['DISTRICT', 'CITY']" :key="value" :value="value" :label="w.coverages[value]" /></ElSelect></ElFormItem>
      <ElFormItem :label="w.effectiveDate"><ElDatePicker v-model="form.date" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled-date="dateDisabled" /></ElFormItem>
      <p>{{ w.effectiveHint }}</p>
      <ElFormItem :label="w.enabled"><ElSwitch v-model="form.enabled" /></ElFormItem>
      <ElAlert v-if="validation || command.message.value" :title="validation || command.message.value" :type="validation || command.failed.value ? 'error' : 'success'" :closable="false" />
    </ElForm>
    <template #footer><ElButton :disabled="command.pending.value" @click="emit('update:open', false)">{{ w.cancel }}</ElButton><ElButton type="primary" :loading="command.pending.value" @click="save">{{ w.save }}</ElButton></template>
  </ElDialog>
</template>
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDatePicker, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch } from '@/shared/ui'
import type { Binding, BindingRequest } from '../models/weather'
import { weatherApi } from '../api/weather'
import { addDays, calendarDay, chinaDay, midnight } from '../models/presentation'
import { useCommand } from '../composables/useCommand'
import w from '../locales/zh-CN'
const props = defineProps<{ open: boolean; building: string; binding: Binding | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: []; pending: [value: boolean] }>()
const command = useCommand()
const validation = ref('')
const editingVersion = ref(0)
const effectiveAfter = ref('')
const form = reactive({ name: '', latitude: undefined as number | undefined, longitude: undefined as number | undefined, cityCode: '', coverage: 'DISTRICT', date: chinaDay(), enabled: true })
const dateDisabled = (date: Date) => calendarDay(date) < '2024-01-01' || calendarDay(date) <= effectiveAfter.value
watch(() => props.open, open => {
  if (!open) return
  validation.value = ''; command.message.value = ''
  const b = props.binding
  // 表单打开时固定版本；后台刷新不能把旧表单静默套到更新的配置版本上。
  editingVersion.value = b?.version ?? 0
  effectiveAfter.value = b ? chinaDay(new Date(b.effectiveFrom)) : ''
  const nextDate = b ? addDays(chinaDay(new Date(b.effectiveFrom)), 1) : chinaDay()
  Object.assign(form, { name: b?.name ?? '', latitude: b?.latitude, longitude: b?.longitude, cityCode: b?.cityCode ?? '', coverage: b?.coverage ?? 'DISTRICT', date: nextDate > chinaDay() ? nextDate : chinaDay(), enabled: b?.enabled ?? true })
})
watch(command.pending, value => emit('pending', value))
async function save() {
  if (!form.name.trim() || form.latitude == null || form.longitude == null || !Number.isFinite(form.latitude) || !Number.isFinite(form.longitude) || Math.abs(form.latitude) > 90 || Math.abs(form.longitude) > 180 || (form.cityCode && !/^\d{9}$/.test(form.cityCode)) || !form.date || form.date < '2024-01-01') { validation.value = w.invalidLocation; return }
  validation.value = ''
  const payload: BindingRequest = { name: form.name.trim(), latitude: form.latitude, longitude: form.longitude, cityCode: form.cityCode || null, coverage: form.coverage, effectiveFrom: midnight(form.date), expectedVersion: editingVersion.value, enabled: form.enabled }
  const result = await command.run(`binding:${props.building}`, payload, key => weatherApi.bind(props.building, payload, key), w.saved)
  if (result) { emit('saved'); emit('update:open', false) }
}
</script>
<style scoped>
.coordinates { display: grid; grid-template-columns: 1fr 1fr; gap: var(--bec-space-group); }
.el-form { margin-top: var(--bec-space-group); }
</style>
