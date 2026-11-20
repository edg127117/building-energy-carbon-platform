import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type {
  AssetEquipmentDetail,
  AssetEquipmentReadings,
  AssetEquipmentTrendHistory,
} from '../models/assets'
import type { AssetLedgerItem } from '../models/ledger'

const copy: Record<string, string> = {
  'assetManagement.ledgerRuntime.title': '设备护照',
  'assetManagement.ledgerRuntime.communicationHeader': '设备通讯：{state}',
  'assetManagement.ledgerRuntime.collapseDetail': '收起详情',
  'assetManagement.ledgerRuntime.emptySelection': '选择设备后查看运行护照',
  'assetManagement.ledgerRuntime.loading': '正在加载设备护照…',
  'assetManagement.ledgerRuntime.loadFailed': '设备护照加载失败',
  'assetManagement.ledgerRuntime.unavailable': '—',
  'assetManagement.ledgerRuntime.location': '位置',
  'assetManagement.ledgerRuntime.unconfigured': '未配置',
  'assetManagement.ledgerRuntime.realtimeTitle': '实时工况与数据质量',
  'assetManagement.ledgerRuntime.runningStatus': '运行状态',
  'assetManagement.ledgerRuntime.communicationStatus': '通讯状态',
  'assetManagement.ledgerRuntime.unknown': '未知',
  'assetManagement.ledgerRuntime.roomSensor.door': '门开关状态',
  'assetManagement.ledgerRuntime.roomSensor.radar': '检测人数',
  'assetManagement.ledgerRuntime.roomSensor.closed': '关',
  'assetManagement.ledgerRuntime.roomSensor.open': '开',
  'assetManagement.ledgerRuntime.roomSensor.peopleUnit': '人',
  'assetManagement.ledgerRuntime.roomSensor.radarHistory': '检测人数趋势',
  'assetManagement.ledgerRuntime.roomSensor.doorHistory': '门开关历史',
  'assetManagement.ledgerRuntime.roomSensor.count': '{count} 人',
  'assetManagement.ledgerRuntime.roomSensor.at': '观测时间 {time}',
  'assetManagement.ledgerRuntime.roomSensor.previous': '最后有效观测 {value} · {time}',
  'assetManagement.ledgerRuntime.operation.DEVICE.RUNNING': '设备运行',
  'assetManagement.ledgerRuntime.operation.DEVICE.STOPPED': '设备停止',
  'assetManagement.ledgerRuntime.operation.DEVICE.UNKNOWN': '设备运行未知',
  'assetManagement.ledgerRuntime.operation.DEVICE.NOT_APPLICABLE': '不适用',
  'assetManagement.ledgerRuntime.operation.COMPRESSOR.RUNNING': '压缩机开启',
  'assetManagement.ledgerRuntime.operation.COMPRESSOR.STOPPED': '压缩机停止',
  'assetManagement.ledgerRuntime.operation.COMPRESSOR.UNKNOWN': '压缩机状态未知',
  'assetManagement.ledgerRuntime.operation.COMPRESSOR.NOT_APPLICABLE': '不适用',
  'assetManagement.ledgerRuntime.communication.ONLINE': '在线',
  'assetManagement.ledgerRuntime.communication.ONLINE_DELAYED': '在线·上报延迟',
  'assetManagement.ledgerRuntime.communication.OFFLINE': '离线',
  'assetManagement.ledgerRuntime.communication.UNKNOWN': '未知',
  'assetManagement.ledgerRuntime.communication.NOT_MONITORED': '未监测',
  'assetManagement.ledgerRuntime.communicationReason.DEVICE_TIMEOUT': '设备通讯超时',
  'assetManagement.ledgerRuntime.communicationReason.NOT_MONITORED': '未纳入通讯监测',
  'assetManagement.ledgerRuntime.communicationReason.REPORT_DELAYED': '设备上报延迟',
  'assetManagement.ledgerRuntime.communicationReason.WAITING_INITIAL_CONTACT': '等待首次通讯',
  'assetManagement.ledgerRuntime.communicationReason.UNKNOWN': '通讯依据暂不可用',
  'assetManagement.ledgerRuntime.pointReadings': '实时测点',
  'assetManagement.ledgerRuntime.readingLoading': '正在加载实时测点…',
  'assetManagement.ledgerRuntime.readingsLoadFailed': '实时测点加载失败',
  'assetManagement.ledgerRuntime.noReadings': '暂无实时测点数据',
  'assetManagement.ledgerRuntime.quality.q0': 'Q0 · 真实数据',
  'assetManagement.ledgerRuntime.quality.q1': 'Q1 · 插值补全',
  'assetManagement.ledgerRuntime.quality.q2': 'Q2 · 典型参考',
  'assetManagement.ledgerRuntime.quality.unknown': '质量未知',
  'assetManagement.ledgerRuntime.usage.available': '允许展示',
  'assetManagement.ledgerRuntime.usage.qualityBlocked': '质量策略已阻断',
  'assetManagement.ledgerRuntime.usage.policySnapshotUnavailable': '质量策略快照不可用',
  'assetManagement.ledgerRuntime.usage.unknown': '使用状态未知',
  'assetManagement.ledgerRuntime.trendTitle': '测点历史趋势',
  'assetManagement.ledgerRuntime.trendPointTitle': '{name}趋势',
  'assetManagement.ledgerRuntime.trendPeriod': '最近 6 小时 · 1 分钟均值',
  'assetManagement.ledgerRuntime.trendSelectPoint': '请选择测点查看历史趋势',
  'assetManagement.ledgerRuntime.trendLoading': '正在加载测点历史…',
  'assetManagement.ledgerRuntime.trendLoadFailed': '测点历史加载失败',
  'assetManagement.ledgerRuntime.trendNoData': '该测点当前时段暂无可展示数据',
  'assetManagement.ledgerRuntime.trendRange': '{start} 至 {end}',
  'assetManagement.ledgerRuntime.trendLast': '最后有效读数 {time}',
  'assetManagement.ledgerRuntime.trendGap': '无有效读数',
  'assetManagement.ledgerRuntime.trendGapNote': '曲线断开处无有效读数',
  'assetManagement.ledgerRuntime.trendTooltip': '{name}：{value}',
  'assetManagement.ledgerRuntime.pointDetails': '测点明细与使用门禁',
  'assetManagement.ledgerRuntime.pointDetailsHint': '展开查看测点状态、时间与使用门禁',
  'assetManagement.ledgerRuntime.pointStatus': '测点状态',
  'assetManagement.ledgerRuntime.eventTime': '事件时间',
  'assetManagement.ledgerRuntime.usageStatus': '使用状态',
  'assetManagement.ledgerRuntime.reason': '原因',
  'assetManagement.ledgerRuntime.nameplateTitle': '静态铭牌与设计参数',
  'assetManagement.ledgerRuntime.manufacturer': '制造商',
  'assetManagement.ledgerRuntime.ratedCapacity': '额定容量',
  'assetManagement.ledgerRuntime.ratedPower': '额定功率',
  'assetManagement.ledgerRuntime.designCop': '设计性能系数',
  'assetManagement.ledgerRuntime.relatedExceptionsTitle': '关联异常',
  'assetManagement.ledgerRuntime.relatedExceptionsHint': '关联异常尚未接入',
  'assetManagement.ledgerRuntime.maintenanceTitle': '维护记录',
  'assetManagement.ledgerRuntime.maintenanceHint': '维护记录尚未接入',
  'assetManagement.ledgerRuntime.workOrdersTitle': '工单',
  'assetManagement.ledgerRuntime.workOrdersHint': '工单尚未接入',
  'assetManagement.ledgerRuntime.createWorkOrder': '新建维修工单',
  'assetManagement.ledgerRuntime.createWorkOrderHint': '工单服务待接入',
  'assetManagement.ledgerRuntime.locateSpace': '空间定位',
  'assetManagement.ledgerRuntime.locateSpaceHint': '空间定位服务待接入',
  'assetManagement.ledgerRuntime.fullArchive': '完整设备护照',
}

vi.mock('@/locales', () => ({
  t: (key: string, params: Record<string, string | number> = {}) =>
    (copy[key] ?? key).replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? '')),
}))

vi.mock('@/shared/charts/ChartView.vue', () => ({
  default: {
    name: 'TestChartView',
    props: ['option', 'accessibleLabel'],
    template: '<div data-test="trend-chart" />',
  },
}))

import EquipmentRuntimePassport from './EquipmentRuntimePassport.vue'

const firstTime = 1_735_689_600_000
const secondTime = 1_735_693_200_000

function equipment(): AssetEquipmentDetail {
  return {
    equipmentId: 'E-1',
    equipmentCode: 'CH-101',
    equipmentName: '1号冷水机组',
    typeCode: 'CHILLER',
    category: '冷水机组',
    buildingId: 'B-1',
    buildingName: 'A座',
    spaceId: 'S-1',
    spaceName: '冷机房',
    systemGroupId: null,
    systemGroupName: null,
    productId: null,
    productName: null,
    status: 'ACTIVE',
    expectedProfileCode: null,
    lastDiscoveredTime: null,
    pointSummary: { total: 2, required: 0, configuredRequired: 0 },
    allowedActions: [],
    updateTime: firstTime,
    manufacturer: null,
    ratedCapacity: null,
    ratedPower: null,
    designCop: null,
    parameterGovernanceStatus: null,
    identities: [],
    references: { spaces: 0, systemGroups: 0, equipment: 0, points: 0, authorizations: 0, children: 0, aliases: 0, identities: 0 },
  }
}

function readings(): AssetEquipmentReadings {
  return {
    equipmentId: 'E-1',
    buildingId: 'B-1',
    generatedAt: secondTime,
    points: [
      {
        pointId: 'P-FLOW', pointCode: 'FLOW_TEMP', pointName: '出水温度', unit: '°C',
        value: 0, eventTime: firstTime, receivedTime: firstTime, dataQuality: 0,
        status: 'HAS_DATA', usageStatus: 'AVAILABLE', reason: null,
      },
      {
        pointId: 'P-RETURN', pointCode: 'RETURN_TEMP', pointName: '回水温度', unit: '°C',
        value: 99.99, eventTime: secondTime, receivedTime: secondTime, dataQuality: 2,
        status: 'HAS_DATA', usageStatus: 'QUALITY_BLOCKED', reason: 'QUALITY_NOT_ALLOWED_BY_DEFAULT',
      },
    ],
  }
}

function trend(): AssetEquipmentTrendHistory {
  return {
    equipmentId: 'E-1',
    buildingId: 'B-1',
    startTime: new Date(firstTime).toISOString(),
    endTime: new Date(secondTime).toISOString(),
    series: [
      { pointCode: 'FLOW_TEMP', pointName: '出水温度', unit: '°C', data: [[firstTime, 0], [firstTime + 60_000, 7.2]] },
      { pointCode: 'RETURN_TEMP', pointName: '回水温度', unit: '°C', data: [[firstTime, 99.99]] },
    ],
  }
}

function ledgerItem(): AssetLedgerItem {
  return {
    equipment: { equipmentId: 'E-1', buildingId: 'B-1' },
    professionalGroup: 'HVAC',
    classificationSource: 'RULE',
    ruleRevision: 3,
    classificationRevision: 2,
    operation: { applicable: true, value: 'RUNNING', reason: 'COMPRESSOR_REPORT', source: 'DEVICE_STATE', observedAt: firstTime, scope: 'COMPRESSOR' },
    communication: { applicable: true, value: 'OFFLINE', reason: 'DEVICE_TIMEOUT', source: 'DEVICE_HEARTBEAT', observedAt: secondTime, scope: 'DEVICE' },
    displayPoint: { status: 'AVAILABLE', reason: null, pointId: 'P-FLOW', pointCode: 'FLOW_TEMP', pointName: '出水温度', unit: '°C', value: 0, eventTime: firstTime, receivedTime: firstTime, dataQuality: 0, usageStatus: 'AVAILABLE' },
  } as AssetLedgerItem
}

function render(props: Record<string, unknown> = {}) {
  return mount(EquipmentRuntimePassport, {
    props: {
      equipment: equipment(),
      ledgerItem: ledgerItem(),
      readings: readings(),
      trend: trend(),
      ...props,
    },
  })
}

describe('EquipmentRuntimePassport', () => {
  it('用真实零值和空时间桶绘制整数人数趋势', () => {
    const wrapper = render({
      ledgerItem: { ...ledgerItem(), roomSensor: { sourceId: 'radar', kind: 'RADAR', currentCount: 0 } },
      trend: { ...trend(), series: [], roomSensors: [
        { sourceId: 'radar', kind: 'RADAR', intervalSeconds: 60, truncated: false, samples: [
          { observedAt: firstTime, count: 0 },
          { observedAt: firstTime + 120_000, count: 1 },
        ] },
      ] },
    })
    expect(wrapper.get('[data-test="trend-title"]').text()).toBe('检测人数趋势')
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      yAxis: unknown
      series: unknown
      tooltip: { formatter: (params: unknown) => string }
    }
    expect(option.yAxis).toMatchObject({ name: '人', min: 0, max: 5, interval: 1 })
    expect(option.series).toMatchObject([{ data: [[firstTime, 0], [firstTime + 60_000, null], [firstTime + 120_000, 1]], step: 'end' }])
    expect(option.tooltip.formatter([{ data: [firstTime, 0] }])).toContain('0 人')
    wrapper.unmount()
  })

  it('以最近在前的开关记录显示门磁历史', () => {
    const wrapper = render({
      ledgerItem: { ...ledgerItem(), roomSensor: { sourceId: 'door', kind: 'DOOR', currentOpen: false } },
      trend: { ...trend(), series: [], roomSensors: [
        { sourceId: 'door', kind: 'DOOR', truncated: false, samples: [
          { observedAt: firstTime, open: true },
          { observedAt: secondTime, open: false },
        ] },
      ] },
    })
    expect(wrapper.get('[data-test="trend-title"]').text()).toBe('门开关历史')
    expect(wrapper.get('[data-test="door-history"]').findAll('strong').map(item => item.text())).toEqual(['关', '开'])
    wrapper.unmount()
  })

  it('区分在线设备的已过期门状态和有效的零人读数', () => {
    const item = ledgerItem()
    const wrapper = render({
      ledgerItem: { ...item, communication: { ...item.communication, value: 'ONLINE', reason: 'LIVE_TELEMETRY' } },
      readings: {
        ...readings(),
        points: [],
        roomSensors: [
          { sourceId: 'door', kind: 'DOOR', currentOpen: null, lastObservedOpen: false, lastObservedAt: firstTime },
          { sourceId: 'radar', kind: 'RADAR', currentCount: 0, lastObservedCount: 0, lastObservedAt: secondTime },
        ],
      } satisfies AssetEquipmentReadings,
    })
    expect(wrapper.get('[data-test="communication-state"]').text()).toContain('在线')
    const cards = wrapper.findAll('[data-test="room-sensor-reading"]')
    expect(cards[0].get('[data-test="room-sensor-value"]').text()).toBe('未知')
    expect(cards[0].text()).toContain('最后有效观测 关')
    expect(cards[1].get('[data-test="room-sensor-value"]').text()).toBe('0 人')
    expect(wrapper.find('[data-test="readings-empty"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('在未选择设备时只显示空态，不把旧读数当作当前设备运行状态', () => {
    const wrapper = render({ equipment: null, readings: null, trend: null })

    expect(wrapper.get('[data-test="passport-empty"]').text()).toBe('选择设备后查看运行护照')
    expect(wrapper.find('[data-test="reading-grid"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="trend-chart"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('保留允许展示的零值，并在质量门禁阻断时隐藏意外携带的数值', () => {
    const wrapper = render()
    const values = wrapper.findAll('[data-test="reading-value"]').map(node => node.text())

    expect(values[0]).toContain('0')
    expect(values[1]).toContain('—')
    expect(wrapper.text()).toContain('QUALITY_BLOCKED')
    expect(wrapper.text()).toContain('QUALITY_NOT_ALLOWED_BY_DEFAULT')
    expect(wrapper.text()).not.toContain('99.99')
    wrapper.unmount()
  })

  it('显示后端设备态；压缩机范围保留开启标签，通讯原因映射为中文说明', () => {
    const wrapper = render()

    expect(wrapper.get('[data-test="operation-state"]').text()).toContain('压缩机开启')
    expect(wrapper.get('[data-test="communication-state"]').text()).toContain('离线')
    expect(wrapper.get('[data-test="communication-state"]').text()).toContain('设备通讯超时')
    expect(wrapper.text()).not.toContain('DEVICE_TIMEOUT')
    expect(wrapper.text()).not.toContain('在线')
    wrapper.unmount()

    const noOperation = render({
      ledgerItem: {
        ...ledgerItem(),
        operation: { applicable: false, value: 'NOT_APPLICABLE', reason: 'METER', source: null, observedAt: null, scope: 'DEVICE' },
        communication: { applicable: false, value: 'UNKNOWN', reason: 'NOT_MONITORED', source: null, observedAt: null, scope: 'DEVICE' },
      },
    })

    expect(noOperation.find('[data-test="operation-state"]').exists()).toBe(false)
    expect(noOperation.get('[data-test="communication-state"]').text()).toContain('未监测')
    expect(noOperation.get('[data-test="communication-state"]').text()).not.toContain('未知')
    noOperation.unmount()

    const delayed = render({
      ledgerItem: {
        ...ledgerItem(),
        communication: { applicable: true, value: 'ONLINE', reason: 'REPORT_DELAYED', source: 'DEVICE_TELEMETRY', observedAt: secondTime, scope: 'DEVICE' },
      },
    })

    expect(delayed.get('[data-test="communication-state"]').text()).toContain('在线·上报延迟')
    expect(delayed.get('[data-test="communication-state"]').text()).toContain('设备上报延迟')
    expect(delayed.text()).not.toContain('REPORT_DELAYED')
    delayed.unmount()

    const waitingInitialContact = render({
      ledgerItem: {
        ...ledgerItem(),
        communication: { applicable: true, value: 'UNKNOWN', reason: 'WAITING_INITIAL_CONTACT', source: 'DEVICE_TELEMETRY', observedAt: null, scope: 'DEVICE' },
      },
    })

    expect(waitingInitialContact.get('[data-test="communication-state"]').text()).toContain('未知')
    expect(waitingInitialContact.get('[data-test="communication-state"]').text()).toContain('等待首次通讯')
    expect(waitingInitialContact.text()).not.toContain('WAITING_INITIAL_CONTACT')
    waitingInitialContact.unmount()
  })

  it('只在用户选择真实测点后发出选择事件，不默认绘制第一条趋势', async () => {
    const wrapper = render()

    expect(wrapper.get('[data-test="trend-no-selection"]').text()).toBe('请选择测点查看历史趋势')
    expect(wrapper.find('[data-test="trend-chart"]').exists()).toBe(false)
    await wrapper.findAll('[data-test="reading-card"]')[1]!.trigger('click')
    expect(wrapper.emitted('selectPoint')).toEqual([['P-RETURN']])
    wrapper.unmount()
  })

  it('按选中测点编码匹配唯一时序，保留零值和完整查询范围', () => {
    const wrapper = render({ selectedPointId: 'P-FLOW' })
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      xAxis: { min: number; max: number }
      series: Array<{ name: string; data: [number, number | null][] }>
      tooltip: { formatter: (params: unknown) => string }
    }

    expect(option.series).toMatchObject([{ name: '出水温度', data: [[firstTime, 0], [firstTime + 60_000, 7.2]], type: 'line', connectNulls: false }])
    expect(option.xAxis).toMatchObject({ min: firstTime, max: secondTime })
    expect(option.tooltip.formatter([{ data: [firstTime, 0] }])).toContain('0 °C')
    expect(option.tooltip.formatter([{ data: [firstTime, 0] }])).toContain('2025')
    expect(wrapper.get('[data-test="trend-range"]').text()).toContain('2025')
    expect(wrapper.get('[data-test="trend-last"]').text()).toContain('最后有效读数')
    expect(wrapper.get('[data-test="trend-title"]').text()).toBe('出水温度趋势')
    expect(wrapper.text()).toContain('最近 6 小时 · 1 分钟均值')
    expect(wrapper.find('[data-test="trend-gap"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('小功率显示瓦，保留 1 W 下探，正常三分钟相连并在六分钟缺口断开', () => {
    const at = (minute: number) => firstTime + minute * 60_000
    const point = { ...readings().points[0]!, pointName: '内机输入功率', unit: 'kW', value: 0.022 }
    const wrapper = render({
      equipment: { ...equipment(), category: 'ELECTRIC_METER' },
      selectedPointId: point.pointId,
      readings: { ...readings(), points: [point] },
      trend: { ...trend(), series: [{
        pointCode: point.pointCode, pointName: point.pointName, unit: 'kW',
        data: [[at(1), 0.022], [at(4), 0.021], [at(7), 0.022], [at(13), 0.022], [at(16), 0.021], [at(19), 0.022]],
      }] },
    })
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      xAxis: { splitNumber: number; axisLabel: { hideOverlap: boolean; formatter: (value: number) => string } }
      yAxis: { name: string; min: number; max: number; interval: number }
      series: Array<{ data: [number, number | null][]; connectNulls: boolean }>
      tooltip: { formatter: (params: unknown) => string }
    }
    expect(option.yAxis).toMatchObject({ name: 'W', min: 0, max: 30, interval: 10 })
    expect(option.xAxis).toMatchObject({ splitNumber: 4, axisLabel: { hideOverlap: true } })
    expect(option.xAxis.axisLabel.formatter(at(4))).toMatch(/^\d{2}:\d{2}$/)
    expect(option.series[0]).toMatchObject({
      connectNulls: false,
      data: [[at(1), 22], [at(4), 21], [at(7), 22], [at(10), null], [at(13), 22], [at(16), 21], [at(19), 22]],
    })
    expect(option.tooltip.formatter([{ data: [at(4), 21] }])).toContain('21 W')
    expect(option.tooltip.formatter([{ data: [at(7), 22] }])).toContain('22 W')
    expect(option.tooltip.formatter([{ data: [at(10), null] }])).toContain('无有效读数')
    expect(wrapper.get('[data-test="trend-title"]').text()).toBe('内机输入功率趋势')
    expect(wrapper.get('[data-test="reading-value"]').text()).toContain('22 W')
    expect(wrapper.get('[data-test="trend-gap"]').text()).toContain('无有效读数')
    wrapper.unmount()
  })

  it('电表频率刻度采用最小显示跨度，并统一保留两位小数', () => {
    const point = { ...readings().points[0]!, pointName: '频率', unit: 'Hz', value: 50 }
    const wrapper = render({
      equipment: { ...equipment(), category: 'ELECTRIC_METER', typeCode: 'ELECTRIC_METER_1P' },
      selectedPointId: point.pointId,
      readings: { ...readings(), points: [point] },
      trend: { ...trend(), series: [{
        pointCode: point.pointCode, pointName: point.pointName, unit: 'Hz',
        data: [[firstTime, 49.96], [firstTime + 60_000, 50], [firstTime + 120_000, 50.04]],
      }] },
    })
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      yAxis: { name: string; min: number; max: number; interval: number; axisLabel: { formatter: (value: number) => string } }
      tooltip: { formatter: (params: unknown) => string }
    }
    expect(option.yAxis).toMatchObject({ name: 'Hz', min: 49.6, max: 50.4, interval: 0.2 })
    expect(option.yAxis.axisLabel.formatter(50)).toBe('50.00')
    expect(option.tooltip.formatter([{ data: [firstTime + 60_000, 50] }])).toContain('50.00 Hz')
    expect(wrapper.get('[data-test="reading-value"]').text()).toContain('50.00 Hz')
    wrapper.unmount()
  })

  it('空调室温的小幅波动按室温模板留白，原始读数保留', () => {
    const point = { ...readings().points[0]!, pointCode: 'IDU14_roomTemp', pointName: '室内温度', value: 19.2 }
    const data = [[firstTime, 19.2], [firstTime + 60_000, 19.3]]
    const wrapper = render({
      equipment: { ...equipment(), category: 'INDOOR_UNIT', typeCode: 'IDU' },
      selectedPointId: point.pointId,
      readings: { ...readings(), points: [point] },
      trend: { ...trend(), series: [{ pointCode: point.pointCode, pointName: point.pointName, unit: '°C', data }] },
    })
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      yAxis: unknown
      series: unknown
    }
    expect(option.yAxis).toMatchObject({ name: '°C', min: 18, max: 20.5, interval: 0.5 })
    expect(option.series).toMatchObject([{ data }])
    wrapper.unmount()
  })

  it('铭牌显示原有参数，隐藏后端参数治理状态', () => {
    const wrapper = render({
      equipment: {
        ...equipment(),
        manufacturer: '大金',
        ratedCapacity: 3.5,
        ratedPower: 1.2,
        designCop: 3,
        parameterGovernanceStatus: 'LEGACY_UNGOVERNED',
      },
    })
    const nameplate = wrapper.get('.runtime-passport__nameplate')
    expect(nameplate.findAll('dt').map(item => item.text())).toEqual(['制造商', '额定容量', '额定功率', '设计性能系数'])
    expect(nameplate.findAll('dd').map(item => item.text())).toEqual(['大金', '3.5', '1.2', '3'])
    wrapper.unmount()
  })

  it('仅把完整护照和收起交给父级，待接入操作保持禁用', async () => {
    const wrapper = render()

    const buttons = wrapper.findAll('button')
    expect(buttons.find(button => button.text() === '新建维修工单')?.attributes('disabled')).toBeDefined()
    expect(buttons.find(button => button.text() === '空间定位')?.attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="收起详情"]').trigger('click')
    await buttons.find(button => button.text() === '完整设备护照')!.trigger('click')
    expect(wrapper.emitted('collapse')).toEqual([[]])
    expect(wrapper.emitted('archive')).toEqual([[]])
    wrapper.unmount()
  })
})
