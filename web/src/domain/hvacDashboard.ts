import type {
  HvacLatestResponse,
  HvacSnapshotResponse,
  LatestIndicator,
  SnapshotPoint,
} from '@/types/hvac'

/**
 * 将后端标准测点编码适配为冻结书中的 19 个展示槽位。
 *
 * MQTT 上报别名先在后端解析成标准编码，快照 API 返回的不是 MQTT 别名；
 * 因此前端必须在这一处显式转换，不能直接用冻结书编码查找接口结果。
 */
export const FROZEN_POINT_DEFINITIONS = [
  { displayCode: 'WCR1_TWin', internalCode: 'WCR1_TWin', label: '冷冻水进水温度', unit: '℃', precision: 1 },
  { displayCode: 'WCR1_TWout', internalCode: 'WCR1_TWout', label: '冷冻水出水温度', unit: '℃', precision: 1 },
  { displayCode: 'WCR1_Flow', internalCode: 'WCR1_GW', label: '冷冻水流量', unit: 'm³/h', precision: 1 },
  { displayCode: 'WCR1_PPE', internalCode: 'WCR1_PPE', label: '瞬时功率', unit: 'kW', precision: 1 },
  { displayCode: 'WCR1_Voltage', internalCode: 'WCR1_Voltage', label: '压缩机电压', unit: 'V', precision: 0 },
  { displayCode: 'WCR1_Current', internalCode: 'WCR1_Current', label: '压缩机电流', unit: 'A', precision: 1 },
  { displayCode: 'WCR1_PF', internalCode: 'WCR1_PF', label: '功率因数', unit: '', precision: 3 },
  { displayCode: 'TOWER1_TCWin', internalCode: 'WCR1_CT_TWin', label: '冷却水进水温度', unit: '℃', precision: 1 },
  { displayCode: 'TOWER1_TCWout', internalCode: 'WCR1_CT_TWout', label: '冷却水出水温度', unit: '℃', precision: 1 },
  { displayCode: 'TOWER1_TWB', internalCode: 'WCR1_CT_TWB', label: '室外湿球温度', unit: '℃', precision: 1 },
  { displayCode: 'PUMP1_Flow', internalCode: 'WCR1_Pc_GW', label: '水泵流量', unit: 'm³/h', precision: 1 },
  { displayCode: 'PUMP1_Pout', internalCode: 'WCR1_Pc_Pout', label: '水泵出口压力', unit: 'Pa', precision: 0 },
  { displayCode: 'PUMP1_Pin', internalCode: 'WCR1_Pc_Pin', label: '水泵入口压力', unit: 'Pa', precision: 0 },
  { displayCode: 'PUMP1_Z', internalCode: 'WCR1_Pc_Z', label: '压力表高度差', unit: 'm', precision: 1 },
  { displayCode: 'PUMP1_Power', internalCode: 'WCR1_Pc_PPE', label: '水泵输入功率', unit: 'kW', precision: 1 },
  { displayCode: 'AHU1_TotalPress', internalCode: 'AHU1_TotalPress', label: '风机全压值', unit: 'Pa', precision: 0 },
  { displayCode: 'AHU1_EtaT', internalCode: 'AHU1_EtaT', label: '风机总效率', unit: '%', precision: 1 },
  { displayCode: 'DBO_TDB', internalCode: 'DBO', label: '室外干球温度', unit: '℃', precision: 1 },
  { displayCode: 'DBO_RH', internalCode: 'RHO', label: '室外相对湿度', unit: '%', precision: 0 },
] as const

export type FrozenPointCode =
  (typeof FROZEN_POINT_DEFINITIONS)[number]['displayCode']

/** 页面固定展示的四项指标及格式，不代表后端接口只返回四条配置。 */
export const INDICATOR_DEFINITIONS = [
  { indicatorCode: 'WCR_COP', label: '冷水机组 COP', precision: 2, unit: '', tone: 'blue' },
  { indicatorCode: 'TOWER_EFF', label: '冷却塔效率', precision: 1, unit: '%', tone: 'green' },
  { indicatorCode: 'PUMP_EFF', label: '水泵效率', precision: 1, unit: '%', tone: 'orange' },
  { indicatorCode: 'AHU_POW_EFF', label: '风系统耗功值', precision: 2, unit: 'W/(m³·h)', tone: 'yellow' },
] as const

export type DashboardPointView = {
  displayCode: FrozenPointCode
  internalCode: string
  label: string
  unit: string
  value: number | null
  displayValue: string
  minute: number | null
  sampleCount: number
  dataQuality: number | null
  qualityLabel: string
  status: string
}

export type DashboardIndicatorView = {
  indicatorId: string | null
  indicatorCode: string
  label: string
  tone: string
  value: number | null
  displayValue: string
  unit: string
  minuteStart: number | null
  status: string
  statusLabel: string
  dataQuality: number | null
  formulaVersion: string | null
  reasonCode: string | null
  missingInputs: string[]
}

export type DashboardPointMap = Record<FrozenPointCode, DashboardPointView>

/** 将后端 Q0/Q1/Q2 质量等级转换为页面文案，空值或未知等级显示“无数据”。 */
function qualityLabel(quality: number | null): string {
  if (quality === 0) return '实测'
  if (quality === 1) return '插值'
  if (quality === 2) return '典型值'
  return '无数据'
}

/** 将公式引擎状态转换为用户可读文案，未知状态保留后端原值便于排查。 */
function indicatorStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    SUCCESS: '计算成功',
    MISSING_INPUT: '缺少输入',
    INVALID_INPUT: '输入无效',
    ENGINE_ERROR: '计算异常',
    NO_DATA: '暂无数据',
  }
  return labels[status] ?? status
}

/**
 * 生成稳定的 19 个测点槽位。接口缺项或 NO_DATA 时保留槽位并显示 `--`，
 * 这样页面不会用默认值掩盖真实的数据缺口。
 */
export function buildPointViews(
  snapshot: HvacSnapshotResponse | null,
): DashboardPointMap {
  const sourceByCode = new Map<string, SnapshotPoint>(
    (snapshot?.points ?? []).map((point) => [point.pointCode, point]),
  )

  return Object.fromEntries(
    FROZEN_POINT_DEFINITIONS.map((definition) => {
      const source = sourceByCode.get(definition.internalCode)
      const available =
        source?.status === 'NORMAL' && source.average !== null
      const view: DashboardPointView = {
        displayCode: definition.displayCode,
        internalCode: definition.internalCode,
        label: source?.pointName || definition.label,
        unit: source?.unit || definition.unit,
        value: available ? source.average : null,
        displayValue: available
          ? source.average!.toFixed(definition.precision)
          : '--',
        minute: source?.minute ?? null,
        sampleCount: source?.sampleCount ?? 0,
        dataQuality: source?.dataQuality ?? null,
        qualityLabel: qualityLabel(source?.dataQuality ?? null),
        status: source?.status ?? 'NO_DATA',
      }
      return [definition.displayCode, view]
    }),
  ) as DashboardPointMap
}

/**
 * 按冻结顺序生成四个指标槽位，并原样保留后端失败原因和缺失输入。
 */
export function buildIndicatorViews(
  latest: HvacLatestResponse | null,
): DashboardIndicatorView[] {
  const sourceByCode = new Map<string, LatestIndicator>(
    (latest?.indicators ?? []).map((indicator) => [
      indicator.indicatorCode,
      indicator,
    ]),
  )

  return INDICATOR_DEFINITIONS.map((definition) => {
    const source = sourceByCode.get(definition.indicatorCode)
    const successful =
      source?.status === 'SUCCESS' && source.value !== null
    return {
      indicatorId: source?.indicatorId ?? null,
      indicatorCode: definition.indicatorCode,
      label: definition.label,
      tone: definition.tone,
      value: successful ? source.value : null,
      displayValue: successful
        ? source.value!.toFixed(definition.precision)
        : '--',
      unit: source?.unit || definition.unit,
      minuteStart: source?.minuteStart ?? null,
      status: source?.status ?? 'NO_DATA',
      statusLabel: indicatorStatusLabel(source?.status ?? 'NO_DATA'),
      dataQuality: source?.dataQuality ?? null,
      formulaVersion: source?.formulaVersion ?? null,
      reasonCode: source?.reasonCode ?? null,
      missingInputs: source?.missingInputs ?? [],
    }
  })
}

/** 按 19 个冻结槽位中状态为 NORMAL 且有平均值的数量计算完整率。 */
export function calculatePointCoverage(points: DashboardPointMap): number {
  const normal = Object.values(points).filter(
    (point) => point.status === 'NORMAL' && point.value !== null,
  ).length
  return Math.round((normal / FROZEN_POINT_DEFINITIONS.length) * 100)
}
