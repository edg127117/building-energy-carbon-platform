<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElOption, ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { listAccessibleBuildings } from '../api/hvac'
import { daikinApi } from '../api/daikin'
import { daikinLabel } from '../models/daikin-display'
import { useDaikinResource } from '../composables/use-daikin-resource'
import DaikinDeviceDetail from './DaikinDeviceDetail.vue'

const props = withDefaults(defineProps<{ alarms?: boolean; history?: boolean }>(), { alarms: false, history: false })
const route = useRoute()
const text = (key: string) => t(`dashboard.daikin.${key}`)
const buildings = useDaikinResource<Awaited<ReturnType<typeof listAccessibleBuildings>>>()
const devices = useDaikinResource<Awaited<ReturnType<typeof daikinApi.devices>>>()
const exceptions = useDaikinResource<Awaited<ReturnType<typeof daikinApi.exceptions>>>()
const building = ref('')
const kind = ref('')
const page = ref(1)
const selected = ref<string | null>(null)
const refreshTick = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
function load(cursor?: string) {
  if (!building.value) return
  if (props.alarms) void exceptions.run(() => daikinApi.exceptions(building.value, props.history, cursor))
  else void devices.run(() => daikinApi.devices(building.value, page.value, kind.value))
}
function refresh() { load(); refreshTick.value++ }
watch(building, () => { selected.value = null; page.value = 1; devices.clear(); exceptions.clear(); load() })
watch(kind, () => { page.value = 1; selected.value = null; load() })
watch(page, () => { selected.value = null; load() })
onMounted(async () => {
  await buildings.run(listAccessibleBuildings)
  if (disposed) return
  const requestedBuilding = typeof route.query.buildingId === 'string' ? route.query.buildingId : ''
  building.value = buildings.data.value?.find(item => item.buildingId === requestedBuilding)?.buildingId ?? buildings.data.value?.[0]?.buildingId ?? ''
  // 深链接只选已授权建筑；设备接口仍独立校验真实归属和菜单。
  if (!props.alarms && building.value === requestedBuilding && typeof route.query.equipmentId === 'string') {
    await Promise.resolve()
    if (!disposed) selected.value = route.query.equipmentId
  }
  // 后台采集独立运行；浏览器只在可见且没有未完成请求时每分钟重读当前页。
  timer = setInterval(() => { if (!document.hidden && !devices.loading.value && !exceptions.loading.value && !props.history) refresh() }, 60000)
})
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <section class="daikin-panel">
    <header>
      <div><h1>{{ text(alarms ? (history ? 'historyAlarms' : 'currentAlarms') : 'title') }}</h1><p>{{ text(alarms ? 'alarmNotice' : 'notice') }}</p></div>
      <div class="filters">
        <ElSelect v-model="building" :placeholder="text('building')" :aria-label="text('building')"><ElOption v-for="item in buildings.data.value ?? []" :key="item.buildingId" :value="item.buildingId" :label="item.buildingName" /></ElSelect>
        <ElSelect v-if="!alarms" v-model="kind" :placeholder="text('all')" :aria-label="text('kind')"><ElOption value="" :label="text('all')" /><ElOption v-for="key in ['INDOOR', 'OUTDOOR']" :key="key" :value="key" :label="text(key)" /></ElSelect>
        <ElButton :disabled="!building" :loading="devices.loading.value || exceptions.loading.value" @click="refresh">{{ text('refresh') }}</ElButton>
      </div>
    </header>
    <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    <ElSkeleton v-if="buildings.loading.value" :rows="5" animated />
    <ElEmpty v-else-if="!building && !buildings.error.value" :description="t('dashboard.noBuilding')" />
    <template v-if="building && !alarms">
      <ElAlert v-if="devices.error.value" :title="devices.error.value" type="error" :closable="false" />
      <ElSkeleton v-if="devices.loading.value" :rows="5" animated />
      <ElTable v-else :data="devices.data.value?.items ?? []" :empty-text="text('none')">
        <ElTableColumn :label="text('equipment')" min-width="180"><template #default="{ row }">{{ row.equipmentName }}<br>{{ row.equipmentCode || row.equipmentId }}</template></ElTableColumn>
        <ElTableColumn :label="text('kind')"><template #default="{ row }">{{ ['INDOOR', 'OUTDOOR'].includes(row.deviceKind) ? text(row.deviceKind) : row.deviceKind }}</template></ElTableColumn>
        <ElTableColumn :label="text('onOff')"><template #default="{ row }">{{ daikinLabel(row.onOff?.value) }}</template></ElTableColumn>
        <ElTableColumn :label="text('mode')"><template #default="{ row }">{{ daikinLabel(row.mode?.value) }}</template></ElTableColumn>
        <ElTableColumn :label="text('state')" min-width="140"><template #default="{ row }"><ElTag :type="row.stale ? 'warning' : 'info'">{{ text(!row.active ? 'inactive' : row.lastValidAt == null ? 'noObservation' : row.stale ? 'stale' : 'normal') }}</ElTag><ElTag v-if="row.hasActiveException" type="danger">{{ text('exception') }}</ElTag></template></ElTableColumn>
        <ElTableColumn :label="text('fresh')" min-width="175"><template #default="{ row }">{{ row.lastValidAt == null ? t('common.missing') : formatDateTime(row.lastValidAt) }}</template></ElTableColumn>
        <ElTableColumn :label="text('detail')"><template #default="{ row }"><ElButton text @click="selected = row.equipmentId">{{ text('detail') }}</ElButton></template></ElTableColumn>
      </ElTable>
      <ElPagination v-if="devices.data.value" v-model:current-page="page" :page-size="20" :total="devices.data.value.total" layout="prev, pager, next, total" />
      <template v-if="selected"><ElButton class="close-detail" @click="selected = null">{{ text('close') }}</ElButton><DaikinDeviceDetail :key="selected" :equipment-id="selected" :refresh-tick="refreshTick" /></template>
    </template>
    <template v-if="building && alarms">
      <ElAlert v-if="exceptions.error.value" :title="exceptions.error.value" type="error" :closable="false" />
      <ElSkeleton v-if="exceptions.loading.value" :rows="5" animated />
      <ElTable v-else :data="exceptions.data.value?.items ?? []" :empty-text="text('empty')">
        <ElTableColumn prop="type" :label="text('type')" /><ElTableColumn prop="sourceId" :label="text('source')" />
        <ElTableColumn :label="text('equipment')"><template #default="{ row }">{{ row.equipmentId ?? t('common.missing') }}</template></ElTableColumn>
        <ElTableColumn :label="text('detected')" min-width="175"><template #default="{ row }">{{ formatDateTime(row.firstDetectedAt) }}</template></ElTableColumn>
        <ElTableColumn v-if="history" :label="text('recovered')" min-width="175"><template #default="{ row }">{{ row.recoveredAt == null ? t('common.missing') : formatDateTime(row.recoveredAt) }}</template></ElTableColumn>
      </ElTable>
      <ElButton @click="load()">{{ text('first') }}</ElButton><ElButton :disabled="!exceptions.data.value?.nextCursor" @click="load(exceptions.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
    </template>
  </section>
</template>

<style scoped>
.close-detail { justify-self: start; }
.daikin-panel { display: grid; gap: var(--bec-space-section); min-width: 0; }
header { display: flex; justify-content: space-between; gap: var(--bec-space-section); flex-wrap: wrap; } h1 { margin: 0; font-size: var(--bec-font-size-system); }
p { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); } .filters { display: flex; gap: var(--bec-space-tight); flex-wrap: wrap; align-items: center; }
.el-select { width: calc(var(--bec-control-height) * 6); } .el-pagination { max-width: 100%; overflow-x: auto; }
@media (max-width: 600px) { .filters { width: 100%; } .el-select { flex: 1; min-width: calc(var(--bec-control-height) * 4); } }
</style>
