import { describe, expect, it } from 'vitest'
import {
  daikinLabel, daikinFieldLabel, daikinFieldExplanation, daikinFieldTone,
  daikinCurrentFieldValue, daikinCurrentValue, daikinCurrentFields,
  daikinQuickStatusCounts, filterDaikinDevicesByQuickStatus,
  groupDaikinDevicesBySystem, groupDaikinDevicesBySpace,
  daikinStructuredDetail, daikinTemperatureSummary, filterDaikinStateEvents,
  temperatureSeries, runtimePeriodTime, temperatureWindow,
} from './daikin-display'
import type { CurrentField, DaikinDevice } from './daikin'

describe('manufacturer display boundaries', () => {
  it('keeps zero and blocked values, breaks gaps without interpolation', () => {
    expect(temperatureSeries([
      { observedAt: 1, value: 0, dataQuality: 0, gapBefore: false, stale: false },
      { observedAt: 600001, value: 22, dataQuality: 0, gapBefore: true, stale: false },
      { observedAt: 660001, value: null, dataQuality: 2, gapBefore: false, stale: false },
    ])).toEqual([[1, 0], [600000, null], [600001, 22], [660001, null]])
  })
  it('does not invent semantics for new manufacturer values', () => {
    expect(daikinLabel('VENDOR_EQUIPMENT')).toBe('设备故障')
    expect(daikinLabel('CONTROLLER_COMMUNICATION')).toBe('控制器通信故障')
    expect(daikinLabel('on')).toBe('开')
    expect(daikinLabel('vendor-future-mode')).toBe('未确认值（原始值：vendor-future-mode）')
    expect(daikinLabel('constructor')).toBe('未确认值（原始值：constructor）')
    expect(daikinLabel('UNCONFIRMED')).toBe('原始值待核验')
    expect(daikinLabel('airFlowSeven')).toBe('风向 7')
    expect(daikinLabel('operating')).toBe('正常')
    expect(daikinFieldLabel('compressorOnOff')).toBe('压缩机启停')
    expect(daikinFieldLabel('onOff')).toBe('启停')
  })
  it('keeps verified fields visible and puts unknown codes in expandable details', () => {
    const field = (fieldName: string, normalizedValue: string): CurrentField => ({
      fieldName, normalizedValue, rawJson: null, status: 'PRESENT', lastValidAt: 1,
      lastAttemptAt: 1, lastAttemptRawJson: null, valueVisible: true, stale: false,
      mappingVersion: 1, lastAttemptMappingVersion: 1,
    })
    const rows = [field('onOff', 'on'), field('airflowDirection', 'airFlowSeven'), field('arth1', '22'), field('modelName', 'FSFP80AB')]
    expect(daikinCurrentFields(rows)).toEqual({ primary: [rows[0], rows[1], rows[3]], extended: [rows[2]] })
    expect(daikinCurrentValue('modelName', 'FSFP80AB')).toBe('FSFP80AB')
    expect(daikinCurrentValue('errorCode', '')).toBe('—')
    expect(daikinCurrentValue('roomTemp', '25.2')).toBe('25.2')
    expect(daikinCurrentValue('errorType', '0')).toBe('正常')
    expect(daikinCurrentValue('controller.status', 'decommissioned')).toBe('已退役')
    expect(daikinCurrentValue('controller.status', 'vendor-new-status')).toBe('厂家状态（原值：vendor-new-status）')
    expect(daikinCurrentValue('compressorOnOff', 'on')).toBe('开')
    expect(daikinCurrentValue('rcProhibitOnOff', 'stopOnly')).toBe('仅允许停止')
    expect(daikinCurrentValue('limitSettempCool', 'off')).toBe('无效')
    expect(daikinCurrentValue('coolLimitsettempU', '32')).toBe('32')
  })
  it('translates every runtime synchronization state for customer display', () => {
    expect(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'UNSUPPORTED', 'EXPIRED'].map(daikinLabel))
      .toEqual(['待执行', '执行中', '等待重试', '已完成', '失败', '不支持', '已过期'])
  })
  it('renders decoded capability structures and preserves unknown state semantics', () => {
    expect(daikinCurrentValue('fanSpeedSetList', '["low","middle","high"]')).toBe('低档、中档、高档')
    expect(daikinCurrentValue('modeSetList', '["fan","dependent","dry"]')).toBe('送风、冷热模式、除湿')
    expect(daikinCurrentValue('onOffModeSetList', '["on","off"]')).toBe('开、关')
    expect(daikinCurrentValue('masterSlaveIds', '["00101","9007199254740993"]')).toBe('00101、9007199254740993')
    expect(daikinCurrentValue('DefaultSetpointRange', '{"min":16,"max":32,"step":1}')).toBe('16～32，步长 1')
    expect(daikinCurrentValue('modeSetList', '[]')).toBe('空列表')
    expect(daikinCurrentValue('modeSetList', 'invalid')).toContain('未确认值')
    expect(daikinCurrentValue('controller.status', 'CommissionPending')).toBe('待开通／调试（参考解释，原值：CommissionPending）')
    expect(daikinFieldLabel('isGroupSlave')).toBe('组内从机')
    expect(daikinFieldLabel('controller.decommissioned')).toBe('控制器停用信息')
  })
  it('promotes successfully decoded protocol fields without promoting invalid attempts', () => {
    const field = (status: string): CurrentField => ({ fieldName: 'modeSetList', normalizedValue: '["fan"]', rawJson: null,
      status, lastValidAt: 1, lastAttemptAt: 1, lastAttemptRawJson: null, valueVisible: true, stale: false,
      mappingVersion: 1, lastAttemptMappingVersion: 1 })
    const valid = field('PRESENT')
    const invalid = field('INVALID')
    expect(daikinCurrentFields([valid, invalid])).toEqual({ primary: [valid], extended: [invalid] })
  })
  it('distinguishes missing reference values from unconfirmed and hidden readings', () => {
    const field: CurrentField = { fieldName: 'arth1', normalizedValue: null, rawJson: null,
      status: 'MISSING', lastValidAt: null, lastAttemptAt: 1, lastAttemptRawJson: null,
      valueVisible: false, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1 }
    expect(daikinCurrentFieldValue(field)).toBe('未提供')
    expect(daikinCurrentFieldValue({ ...field, fieldName: 'controller.decommissioned' })).toBe('未提供')
    const unconfirmed: CurrentField = { ...field, status: 'UNCONFIRMED', lastAttemptRawJson: '25' }
    expect(daikinCurrentFieldValue(unconfirmed)).toBe('—')
    expect(daikinCurrentFieldValue({ ...field, fieldName: 'roomTemp', normalizedValue: '25', status: 'PRESENT' })).toBe('—')
    expect(daikinCurrentFieldValue({ ...field, fieldName: 'roomTemp', normalizedValue: '25', valueVisible: true })).toBe('25')
    expect(daikinFieldExplanation('arth1')).toContain('不替代室温')
    expect(daikinFieldExplanation('controller.decommissioned')).toContain('不等于未停用')
    expect(daikinFieldExplanation('controller.status')).toContain('不据此判断')
    expect(daikinFieldExplanation('constructor')).toBeUndefined()
    expect(daikinCurrentValue('controller.status', 'constructor')).toBe('厂家状态（原值：constructor）')
  })
  it('uses manufacturer period timezone independently of browser timezone', () => {
    expect(runtimePeriodTime(Date.parse('2026-09-16T16:00:00Z'), 'Asia/Shanghai')).toContain('2026/09/17')
    expect(runtimePeriodTime(0, 'unsupported-zone')).toBe('1970-01-01T00:00:00.000Z')
  })
  it('clips a rolling retention boundary but rejects future and oversized windows', () => {
    const now = 100 * 86400000
    expect(temperatureWindow([10 * 86400000 - 1, now - 1], now)).toEqual([10 * 86400000, now - 1])
    expect(temperatureWindow([0, now], now)).toBeNull()
    expect(temperatureWindow([now - 1000, now + 1], now)).toBeNull()
    expect(temperatureWindow(null, now)).toBeNull()
  })
  it('groups devices by multi-split system and space while preserving outdoor unit header on filtered views', () => {
    const makeDevice = (partial: Partial<DaikinDevice> & Pick<DaikinDevice, 'equipmentId' | 'equipmentCode' | 'equipmentName' | 'deviceKind'>): DaikinDevice => ({
      identityId: partial.equipmentId,
      pendingId: partial.equipmentId,
      buildingId: 'BLD001',
      spaceId: 'SP1',
      systemGroupId: 'SYS-1',
      mappingVersion: 1,
      active: true,
      stale: false,
      lastValidAt: 1000,
      onOff: null,
      mode: null,
      unitStatus: { value: 'stopped', status: 'PRESENT', lastValidAt: 1000, stale: false },
      hasActiveException: false,
      ...partial,
    })
    const odu = makeDevice({ equipmentId: 'odu-2', equipmentCode: 'ODU2', equipmentName: '大金3F-6空调外机', deviceKind: 'OUTDOOR', spaceId: 'SP-ROOF' })
    const iduOn = makeDevice({
      equipmentId: 'idu-2', equipmentCode: 'IDU2', equipmentName: '大金内机-B308-1', deviceKind: 'INDOOR',
      onOff: { value: 'on', status: 'PRESENT', lastValidAt: 1000, stale: false },
      mode: { value: 'cooling', status: 'PRESENT', lastValidAt: 1000, stale: false },
      unitStatus: { value: 'operating', status: 'PRESENT', lastValidAt: 1000, stale: false },
    })
    const iduStale = makeDevice({
      equipmentId: 'idu-3', equipmentCode: 'IDU3', equipmentName: '大金内机-B308-2', deviceKind: 'INDOOR',
      onOff: { value: 'off', status: 'PRESENT', lastValidAt: 1000, stale: true },
      stale: true,
      hasActiveException: true,
    })
    const list = [odu, iduOn, iduStale]
    expect(daikinQuickStatusCounts(list)).toEqual({ ALL: 3, RUNNING: 1, STOPPED: 2, EXCEPTION: 1, STALE: 1 })
    const runningOnly = filterDaikinDevicesByQuickStatus(list, 'RUNNING')
    expect(runningOnly).toEqual([iduOn])
    expect(filterDaikinDevicesByQuickStatus(list, 'EXCEPTION')).toEqual([iduStale])

    const sysGroupsWhenFiltered = groupDaikinDevicesBySystem(runningOnly, list)
    expect(sysGroupsWhenFiltered).toHaveLength(1)
    expect(sysGroupsWhenFiltered[0]?.outdoorUnit?.equipmentCode).toBe('ODU2')
    expect(sysGroupsWhenFiltered[0]?.indoorUnits.map(i => i.equipmentCode)).toEqual(['IDU2'])
    expect(sysGroupsWhenFiltered[0]?.runningCount).toBe(1)

    const spaceGroups = groupDaikinDevicesBySpace(list, [{ spaceId: 'SP1', spaceName: '3F B308办公区' }])
    expect(spaceGroups.map(g => g.spaceName)).toEqual(['未分配空间', '3F B308办公区'])

    const splitRoomDevices = [
      makeDevice({ equipmentId: 'idu-b308-2', equipmentCode: 'IDU4', equipmentName: '大金内机-B308-2', deviceKind: 'INDOOR', spaceId: 'SP-308-2' }),
      makeDevice({ equipmentId: 'idu-b308-1', equipmentCode: 'IDU2', equipmentName: '大金内机-B308-1', deviceKind: 'INDOOR', spaceId: 'SP-308-1' }),
      makeDevice({ equipmentId: 'idu-b302-1', equipmentCode: 'IDU3', equipmentName: '大金内机-B302-1', deviceKind: 'INDOOR', spaceId: 'SP-302-1' }),
    ]
    const mergedGroups = groupDaikinDevicesBySpace(splitRoomDevices, [
      { spaceId: 'SP-308-1', spaceName: 'B308-1' },
      { spaceId: 'SP-308-2', spaceName: 'B308-2' },
      { spaceId: 'SP-302-1', spaceName: 'B302-1' },
    ])
    expect(mergedGroups.map(g => [g.spaceName, g.devices.map(d => d.equipmentName)])).toEqual([
      ['B302', ['大金内机-B302-1']],
      ['B308', ['大金内机-B308-1', '大金内机-B308-2']],
    ])
  })
  it('structures 37 protocol fields with semantic tones and filters state events by field and time window', () => {
    expect(daikinFieldTone('onOff', 'on')).toBe('success')
    expect(daikinFieldTone('onOff', 'off')).toBe('muted')
    expect(daikinFieldTone('mode', 'cooling')).toBe('primary')
    expect(daikinFieldTone('mode', 'heating')).toBe('warning')
    expect(daikinFieldTone('unitStatus', 'operating')).toBe('success')
    expect(daikinFieldTone('unitStatus', 'stopped')).toBe('muted')
    expect(daikinFieldTone('inEquipmentError', 'true')).toBe('danger')

    const makeField = (fieldName: string, normalizedValue: string | null, status = 'PRESENT', valueVisible = true): CurrentField => ({
      fieldName, normalizedValue, rawJson: null, status, lastValidAt: 100, lastAttemptAt: 100,
      lastAttemptRawJson: null, valueVisible, stale: false, mappingVersion: 1, lastAttemptMappingVersion: 1,
    })
    const indoorFields = [
      makeField('roomTemp', '24.8'),
      makeField('temperature', '24.0'),
      makeField('onOff', 'on'),
      makeField('mode', 'cooling'),
      makeField('fanSpeed', 'high'),
      makeField('airflowDirection', 'airFlowTwo'),
      makeField('unitStatus', 'operating'),
      makeField('isFilterDirty', 'false'),
      makeField('formalName', '天花板嵌入式'),
      makeField('modelName', 'FSFP71AB'),
      makeField('masterSlaveFlag', 'Slave'),
      makeField('rcProhibitOnOff', 'on'),
      makeField('rcProhibitOpMode', 'on'),
      makeField('rcProhibitSetpoint', 'on'),
      makeField('coolLimitsettempL', '16'),
      makeField('coolLimitsettempU', '32'),
      makeField('limitSettempCool', 'off'),
      makeField('arth1', null, 'MISSING', false),
    ]
    const structuredIndoor = daikinStructuredDetail(indoorFields, 'INDOOR')
    expect(structuredIndoor.roomTempText).toBe('24.8')
    expect(structuredIndoor.setTempText).toBe('24.0')
    expect(structuredIndoor.healthBadgeLabel).toBe('运行正常')
    expect(structuredIndoor.healthBadgeType).toBe('success')
    expect(structuredIndoor.coreTiles.map(t => `${t.label}:${t.value}:${t.tone}`)).toEqual([
      '启停:开:success', '模式:制冷:primary', '风速档位:高档:primary', '风向:风向 2:primary',
    ])
    expect(structuredIndoor.capabilityRows.find(r => r.key === 'rcPermissions')?.value).toBe('允许 / 允许 / 允许')
    expect(structuredIndoor.capabilityRows.find(r => r.key === 'coolLimit')?.value).toBe('16～32 °C（未启用限制）')
    expect(structuredIndoor.extendedRows.map(r => r.fieldName)).toEqual(['arth1'])

    const outdoorFields = [
      makeField('compressorOnOff', 'off'),
      makeField('unitStatus', 'stopped'),
      makeField('modelName', 'RUCXYQ40BB'),
      makeField('isFilterDirty', null, 'MISSING', false),
    ]
    const structuredOutdoor = daikinStructuredDetail(outdoorFields, 'OUTDOOR')
    expect(structuredOutdoor.coreTiles.map(t => `${t.label}:${t.value}`)).toEqual([
      '压缩机启停:关', '机组状态:停止', '设备型号:RUCXYQ40BB',
    ])
    expect(structuredOutdoor.healthRows.map(r => r.key)).toEqual(['unitStatus'])

    const events = [
      { eventId: 1, fieldName: 'onOff', beforeNormalizedValue: 'off', afterNormalizedValue: 'on', previousObservedAt: 1000, observedAt: 2000, afterGap: false },
      { eventId: 2, fieldName: 'unitStatus', beforeNormalizedValue: 'stopped', afterNormalizedValue: 'operating', previousObservedAt: 1000, observedAt: 2000, afterGap: true },
      { eventId: 3, fieldName: 'onOff', beforeNormalizedValue: 'on', afterNormalizedValue: 'off', previousObservedAt: 5000, observedAt: 6000, afterGap: false },
    ]
    expect(filterDaikinStateEvents(events, 'onOff', null).map(e => e.eventId)).toEqual([1, 3])
    expect(filterDaikinStateEvents(events, '', [1500, 3000]).map(e => e.eventId)).toEqual([1, 2])

    expect(daikinTemperatureSummary(
      [{ observedAt: 1, value: 24.5, dataQuality: 0, gapBefore: false, stale: false }],
      [{ observedAt: 1, value: 25.0, dataQuality: 0, gapBefore: true, stale: false }],
    )).toEqual({ sampleCount: 2, gapCount: 1 })
  })
})
