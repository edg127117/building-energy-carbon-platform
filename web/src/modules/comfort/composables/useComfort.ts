import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue'
import { alarmsApi } from '@/modules/alarms/public'
import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { chinaDay, getBuildingCurrentDayWeather, listAccessibleWeatherBuildings } from '@/modules/weather/public'
import { comfortApi, comfortQueryFailure, type ComfortParameterUpdate } from '../api/comfort'
import copy from '../locales/zh-CN'

function useQuery<T>(key: () => string, revision: Ref<number>, request: (signal: AbortSignal) => Promise<T>) {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref('')

  watch([key, revision], async ([target], previous, onCleanup) => {
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    if (target !== previous?.[0]) {
      data.value = null
      error.value = ''
    }
    loading.value = !!target
    if (!target) return

    try {
      const result = await request(controller.signal)
      // 切换查询范围后不接收迟到的旧响应，即使底层请求没有响应取消。
      if (!controller.signal.aborted) {
        data.value = result
        error.value = ''
      }
    } catch (failure) {
      if (controller.signal.aborted) return
      const { denied, invalidTarget } = comfortQueryFailure(failure)
      if (denied || invalidTarget) data.value = null
      error.value = denied ? copy.forbidden : data.value ? copy.refreshFailed : copy.loadFailed
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  }, { immediate: true, flush: 'sync' })

  return { data, loading, error }
}

export function useComfort() {
  const buildingChoice = ref('')
  const roomChoice = ref('')
  const alarmPage = ref(1)
  const manualRevision = ref(0)
  const observationRevision = ref(0)
  const overviewRevision = ref(0)
  const electricityRevision = ref(0)
  const parameterRevision = ref(0)
  const now = ref(Date.now())
  const pageVisible = ref(!document.hidden)
  const day = computed(() => chinaDay(new Date(now.value)))
  const buildings = useQuery(() => 'buildings', manualRevision, listAccessibleWeatherBuildings)
  const buildingId = computed(() => buildings.data.value?.find(item => item.buildingId === buildingChoice.value)?.buildingId
    ?? buildings.data.value?.[0]?.buildingId ?? '')
  const weather = useQuery(() => buildingId.value ? JSON.stringify([buildingId.value, day.value]) : '', observationRevision,
    signal => getBuildingCurrentDayWeather(buildingId.value, signal))
  const rooms = useQuery(() => buildingId.value, observationRevision, signal => comfortApi.spaces(buildingId.value, signal))
  const roomId = computed(() => rooms.data.value?.find(item => item.spaceId === roomChoice.value)?.spaceId
    ?? rooms.data.value?.[0]?.spaceId ?? '')
  const doorChoice = ref<{ spaceId: string; equipmentId: string } | null>(null)
  const doorEquipmentId = computed(() => doorChoice.value?.spaceId === roomId.value ? doorChoice.value.equipmentId : undefined)
  const alarmPageSize = 10
  const alarmKey = () => roomId.value ? JSON.stringify([buildingId.value, roomId.value]) : ''
  const requestAlarms = (page: number, signal: AbortSignal) => alarmsApi.events(buildingId.value, {
    history: false, type: 'DEVICE', spaceId: roomId.value, state: 'ACTIVE', page, size: alarmPageSize,
  }, signal)
  watch(alarmKey, () => { alarmPage.value = 1 }, { flush: 'sync' })
  const alarms = useQuery(alarmKey, observationRevision, signal => requestAlarms(1, signal))
  const followingAlarms = useQuery(() => alarmPage.value > 1 && alarmKey()
    ? JSON.stringify([alarmKey(), alarmPage.value]) : '', observationRevision, signal => requestAlarms(alarmPage.value, signal))
  const alarmDetails = computed(() => alarmPage.value === 1 ? alarms : followingAlarms)

  const overview = useQuery(() => roomId.value, overviewRevision,
    signal => comfortApi.overview(roomId.value, signal))
  const roomDay = () => roomId.value ? JSON.stringify([roomId.value, day.value]) : ''
  const trends = useQuery(roomDay, observationRevision,
    signal => comfortApi.trends(roomId.value, signal))
  const electricity = useQuery(roomDay, electricityRevision, signal => comfortApi.electricity(roomId.value, signal))
  const occupancy = useQuery(roomDay, observationRevision, signal => comfortApi.occupancy(roomId.value, signal))
  const doorStats = useQuery(() => roomId.value ? JSON.stringify([roomId.value, day.value, doorEquipmentId.value]) : '',
    observationRevision, async signal => {
      const selected = doorEquipmentId.value
      if (!selected) return comfortApi.doorStats(roomId.value, signal)
      try {
        return await comfortApi.doorStats(roomId.value, signal, selected)
      } catch (failure) {
        // 安装对象或归属变更后回到当前房间摘要，避免选择失效后无法重新打开明细。
        if (!signal.aborted && comfortQueryFailure(failure).invalidTarget) doorChoice.value = null
        throw failure
      }
    })
  const hvac = useQuery(() => roomId.value, observationRevision, signal => comfortApi.hvacStatus(roomId.value, signal))
  const parameters = useQuery(() => roomId.value, parameterRevision, signal => comfortApi.parameters(roomId.value, signal))
  const refreshing = computed(() => buildings.loading.value || rooms.loading.value || overview.loading.value
    || trends.loading.value || weather.loading.value || occupancy.loading.value || doorStats.loading.value || hvac.loading.value
    || parameters.loading.value || electricity.loading.value)

  const pendingParameterKeys = new Map<string, string>()
  async function saveParameters(spaceId: string, update: ComfortParameterUpdate) {
    // 响应丢失时，同一房间的同一份提交沿用旧键。
    const signature = JSON.stringify([spaceId, update])
    const key = pendingParameterKeys.get(signature) ?? createIdempotencyKey()
    pendingParameterKeys.set(signature, key)
    const saved = await comfortApi.saveParameters(spaceId, update, key)
    pendingParameterKeys.delete(signature)
    if (roomId.value !== spaceId) return
    // 使保存前正在进行的查询失效，摘要和图表先应用当前房间的保存响应。
    parameterRevision.value++
    parameters.data.value = saved
    parameters.error.value = ''
    if ('dynamicEmissionFactor' in (update.manualInputs ?? {})) {
      if (electricity.data.value) {
        electricity.data.value = { ...electricity.data.value, carbonRateKgPerHour: null, carbonTodayKg: null, carbonHours: [],
          carbonStatus: saved.manualInputs.dynamicEmissionFactor == null ? 'NO_FACTOR' : 'INCOMPLETE' }
      }
      electricityRevision.value++
    }
    if (Object.keys(update.manualInputs ?? {}).some(key => key !== 'airTemperature' && key !== 'dynamicEmissionFactor')) {
      // 新参数已回显，等后端重新计算再展示 PMV，避免旧结果与新配置一起出现。
      if (overview.data.value) overview.data.value = { ...overview.data.value, pmv: null }
      overviewRevision.value++
    }
  }

  function refreshObservation() {
    now.value = Date.now()
    observationRevision.value++
    overviewRevision.value++
    electricityRevision.value++
  }

  function refresh() {
    manualRevision.value++
    parameterRevision.value++
    refreshObservation()
  }

  function selectBuilding(id: string) {
    buildingChoice.value = id
    roomChoice.value = ''
  }
  function selectRoom(id: string) { roomChoice.value = id }
  function selectDoor(equipmentId: string) {
    doorChoice.value = { spaceId: roomId.value, equipmentId }
  }

  function onVisible() {
    pageVisible.value = !document.hidden
    if (pageVisible.value) refreshObservation()
  }
  let timer: ReturnType<typeof setInterval>
  onMounted(() => {
    document.addEventListener('visibilitychange', onVisible)
    timer = setInterval(() => { if (!refreshing.value) onVisible() }, 30000)
  })
  onBeforeUnmount(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { buildings, buildingId, rooms, roomId, overview, trends, electricity, weather, occupancy, doorStats, hvac, parameters,
    alarms, alarmDetails, alarmPage, alarmPageSize, day, now, pageVisible, refreshing, refresh, selectBuilding, selectRoom, selectDoor,
    saveParameters }
}
