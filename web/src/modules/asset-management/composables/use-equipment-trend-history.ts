import { computed, ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { getEquipmentTrendHistory } from '../api/assets'
import type { AssetEquipmentTrendQuery, AssetPointReading, AssetPointTrendSeries } from '../models/assets'
import type { MeterTrendRecord } from '../components/meter/MeterRealtimeTrendChart.vue'
import { extractTrendValues } from '../components/meter/meter-display'

export type TrendRangeType = 'realtime' | '6h' | 'today' | '24h' | 'custom'
export type TrendDateRange = [Date, Date] | null

// 实时模式下最大保留点数（10秒上报一次，1小时保留 360 点，复用中央空调低内存实践）
export const MAX_REALTIME_POINTS = 360

// 大数据集分界线：>= 1000 时禁用补间动画与散点渲染，防止 Canvas/DOM 卡顿
export const LARGE_DATASET_POINT_COUNT = 1000

// 会话级内存缓存：避免用户切换抽屉或关闭再打开后曲线从零重新等待
const sessionTrendCache = new Map<string, MeterTrendRecord[]>()

export function clearSessionTrendCache(equipmentId?: string): void {
  if (equipmentId) {
    sessionTrendCache.delete(equipmentId)
  } else {
    sessionTrendCache.clear()
  }
}

/**
 * 根据所选时间范围计算历史时序查询参数与采样步长。
 */
export function calculateQueryTimeRange(
  rangeType: TrendRangeType,
  customRange: TrendDateRange,
  now = Date.now(),
): AssetEquipmentTrendQuery {
  let startMs: number
  let endMs = now

  switch (rangeType) {
    case 'realtime':
      startMs = now - 60 * 60 * 1000 // 近1小时
      return {
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        intervalSeconds: 10,
      }
    case '6h':
      startMs = now - 6 * 60 * 60 * 1000
      return {
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        intervalSeconds: 60,
      }
    case 'today': {
      const todayStart = new Date(now)
      todayStart.setHours(0, 0, 0, 0)
      startMs = todayStart.getTime()
      return {
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        intervalSeconds: 120,
      }
    }
    case '24h':
      startMs = now - 24 * 60 * 60 * 1000
      return {
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        intervalSeconds: 120,
      }
    case 'custom': {
      if (customRange && customRange[0] && customRange[1]) {
        startMs = customRange[0].getTime()
        endMs = customRange[1].getTime()
      } else {
        startMs = now - 60 * 60 * 1000
      }
      const spanSeconds = Math.max(1, Math.round((endMs - startMs) / 1000))
      // 动态降采样步长：目标点数保持在 300~600 之间，避免前端超大开销
      const interval = Math.max(10, Math.round(spanSeconds / 500))
      return {
        startTime: new Date(startMs).toISOString(),
        endTime: new Date(endMs).toISOString(),
        intervalSeconds: interval,
      }
    }
  }
}

/**
 * 将后端返回的扁平化测点时序二维数组序列转换为前端图表专用的 MeterTrendRecord 列表。
 */
export function convertSeriesToTrendRecords(
  series: AssetPointTrendSeries[] | undefined | null,
  phase: '3P' | '1P',
): MeterTrendRecord[] {
  if (!series || series.length === 0) return []
  const map = new Map<number, MeterTrendRecord>()

  for (const s of series) {
    const code = (s.pointCode ?? '').toUpperCase()
    const isPower =
      code.endsWith('_P_TOTAL') ||
      code === 'P_TOTAL' ||
      code.endsWith('_P') ||
      code === 'P' ||
      code.endsWith('_POWER') ||
      code === 'POWER' ||
      code.endsWith('_PTOTAL')
    const isUA =
      code.endsWith('_UA') ||
      code.endsWith('_U_A') ||
      code === 'UA' ||
      code === 'U_A' ||
      code.endsWith('_U') ||
      code === 'U' ||
      code.endsWith('_VOLTAGE')
    const isIA =
      code.endsWith('_IA') ||
      code.endsWith('_I_A') ||
      code === 'IA' ||
      code === 'I_A' ||
      (phase === '1P' && (code.endsWith('_I') || code === 'I' || code.endsWith('_CURRENT')))
    const isIB = code.endsWith('_IB') || code.endsWith('_I_B') || code === 'IB' || code === 'I_B'
    const isIC = code.endsWith('_IC') || code.endsWith('_I_C') || code === 'IC' || code === 'I_C'

    if (!Array.isArray(s.data)) continue

    for (const item of s.data) {
      if (!Array.isArray(item) || item.length < 2) continue
      const [timestamp, val] = item
      if (typeof timestamp !== 'number' || Number.isNaN(timestamp)) continue

      let rec = map.get(timestamp)
      if (!rec) {
        rec = {
          time: timestamp,
          power: null,
          currentA: null,
          currentB: null,
          currentC: null,
          voltage: null,
        }
        map.set(timestamp, rec)
      }

      if (isPower) {
        rec.power = val
      } else if (isUA) {
        rec.voltage = val
      } else if (isIA) {
        rec.currentA = val
      } else if (isIB) {
        rec.currentB = val
      } else if (isIC) {
        rec.currentC = val
      }
    }
  }

  return Array.from(map.values()).sort((a, b) => a.time - b.time)
}

function isApiNotImplemented(reason: unknown): boolean {
  if (reason && typeof reason === 'object') {
    const r = reason as Record<string, unknown>
    if (r.status === 404 || r.status === 501 || r.status === 405 || r.kind === 'request') {
      return true
    }
    if ('response' in r && typeof r.response === 'object' && r.response !== null) {
      const resp = r.response as { status?: number }
      if (resp.status === 404 || resp.status === 501 || resp.status === 405) {
        return true
      }
    }
  }
  return false
}

/**
 * 电表时序走势与历史数据管理 Composable。
 * - 支持实时追加（近1小时滑动窗口）与历史回溯（近6h/今日/近24h/自定义）；
 * - 接口未就绪时平滑降级（apiPending 标记），绝不打断前端实时监控；
 * - 内存会话缓存保障重新开启弹窗时波形连贯；
 * - 超过 1,000 点自动标记 isLargeDataset 禁用补间动画，保障前端流畅运行。
 */
export function useEquipmentTrendHistory() {
  const records = ref<MeterTrendRecord[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const apiPending = ref(false)
  const rangeType = ref<TrendRangeType>('realtime')
  const customRange = ref<TrendDateRange>(null)

  let generation = 0

  const isLargeDataset = computed(() => records.value.length >= LARGE_DATASET_POINT_COUNT)

  function init(equipmentId: string, phase: '3P' | '1P'): void {
    // 1. 优先从内存缓存呈现，避免抽屉重新打开瞬间空白从零等待
    const cached = sessionTrendCache.get(equipmentId)
    if (cached && cached.length > 0) {
      records.value = [...cached]
    }
    // 2. 发起历史拉取
    loadHistory(equipmentId, phase)
  }

  async function loadHistory(equipmentId: string, phase: '3P' | '1P'): Promise<void> {
    if (!equipmentId) return
    const owner = ++generation
    loading.value = true
    error.value = null

    const query = calculateQueryTimeRange(rangeType.value, customRange.value)

    try {
      const result = await getEquipmentTrendHistory(equipmentId, query)
      if (owner !== generation) return

      const converted = convertSeriesToTrendRecords(result.series, phase)
      records.value = converted
      apiPending.value = false
      if (rangeType.value === 'realtime') {
        sessionTrendCache.set(equipmentId, converted)
      }
    } catch (reason) {
      if (owner !== generation) return

      if (isApiNotImplemented(reason)) {
        apiPending.value = true
        // 后端接口建设中：保留已有记录与缓存，平滑降级
        const cached = sessionTrendCache.get(equipmentId)
        if (cached && cached.length > 0 && records.value.length === 0) {
          records.value = [...cached]
        }
      } else {
        error.value = requestErrorMessage(reason)
      }
    } finally {
      if (owner === generation) {
        loading.value = false
      }
    }
  }

  function appendRealtimeReading(
    pts: AssetPointReading[],
    generatedAt: number,
    equipmentId: string,
    phase: '3P' | '1P',
  ): void {
    // 仅在“实时追踪”模式下追加；历史回溯模式下锁定所选历史区间波形
    if (rangeType.value !== 'realtime') {
      return
    }

    const trend = extractTrendValues(pts, phase)
    const q = pts.length > 0 ? pts[0].dataQuality : 0

    const newRecord: MeterTrendRecord = {
      time: generatedAt || Date.now(),
      power: trend.power,
      currentA: trend.currentA,
      currentB: trend.currentB,
      currentC: trend.currentC,
      voltage: trend.voltage,
      dataQuality: q,
    }

    const last = records.value[records.value.length - 1]
    if (last && last.time === newRecord.time && last.power === newRecord.power) {
      return
    }

    // 追加并维持滑动窗口（最大 360 点）
    records.value.push(newRecord)
    if (records.value.length > MAX_REALTIME_POINTS) {
      records.value.shift()
    }

    // 同步更新会话缓存
    sessionTrendCache.set(equipmentId, [...records.value])
  }

  function setRangeType(type: TrendRangeType, equipmentId: string, phase: '3P' | '1P'): void {
    rangeType.value = type
    if (type !== 'custom') {
      loadHistory(equipmentId, phase)
    }
  }

  function setCustomRange(range: TrendDateRange, equipmentId: string, phase: '3P' | '1P'): void {
    customRange.value = range
    if (range && range[0] && range[1]) {
      loadHistory(equipmentId, phase)
    }
  }

  function refresh(equipmentId: string, phase: '3P' | '1P'): void {
    loadHistory(equipmentId, phase)
  }

  function clear(): void {
    ++generation
    records.value = []
    loading.value = false
    error.value = null
  }

  return {
    records,
    loading,
    error,
    apiPending,
    rangeType,
    customRange,
    isLargeDataset,
    init,
    loadHistory,
    appendRealtimeReading,
    setRangeType,
    setCustomRange,
    refresh,
    clear,
  }
}
