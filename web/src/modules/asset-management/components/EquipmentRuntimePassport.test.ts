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
  'assetManagement.ledgerRuntime.trendTitle': '关联读数趋势',
  'assetManagement.ledgerRuntime.trendSelectPoint': '请选择测点查看关联走势',
  'assetManagement.ledgerRuntime.trendLoading': '正在加载关联读数趋势…',
  'assetManagement.ledgerRuntime.trendLoadFailed': '关联读数趋势加载失败',
  'assetManagement.ledgerRuntime.trendNoData': '该测点当前时段暂无可展示数据',
  'assetManagement.ledgerRuntime.trendAccessible': '关联测点走势',
  'assetManagement.ledgerRuntime.trendRange': '{start} 至 {end}',
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
  'assetManagement.ledgerRuntime.parameterGovernanceStatus': '参数治理状态',
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
      { pointCode: 'FLOW_TEMP', pointName: '出水温度', unit: '°C', data: [[firstTime, 0], [secondTime, 7.2]] },
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

    expect(wrapper.get('[data-test="trend-no-selection"]').text()).toBe('请选择测点查看关联走势')
    expect(wrapper.find('[data-test="trend-chart"]').exists()).toBe(false)
    await wrapper.findAll('[data-test="reading-card"]')[1]!.trigger('click')
    expect(wrapper.emitted('selectPoint')).toEqual([['P-RETURN']])
    wrapper.unmount()
  })

  it('按选中测点编码匹配唯一真实时序，并把实际时间范围和零值交给图表', () => {
    const wrapper = render({ selectedPointId: 'P-FLOW' })
    const option = wrapper.findComponent({ name: 'TestChartView' }).props('option') as {
      xAxis: { min: number; max: number }
      series: Array<{ name: string; data: [number, number | null][] }>
      tooltip: { formatter: (params: unknown) => string }
    }

    expect(option.series).toEqual([{ name: '出水温度', data: [[firstTime, 0], [secondTime, 7.2]], type: 'line', connectNulls: false, showSymbol: true, symbol: 'circle' }])
    expect(option.xAxis).toMatchObject({ min: firstTime, max: secondTime })
    expect(option.tooltip.formatter([{ data: [firstTime, 0] }])).toContain('0 °C')
    expect(option.tooltip.formatter([{ data: [firstTime, 0] }])).toContain('2025')
    expect(wrapper.get('[data-test="trend-range"]').text()).toContain('2025')
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
