import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElSelect, ElTree, ElTabs } from '@/shared/ui'
import * as assets from '../api/assets'
import { getMeterCoverage, listMeterCoverageCandidates, listMeterCoverages, listMeterCoverageHistory } from '../api/meter-coverage'
import type { AssetEquipmentDetail, AssetPointReading } from '../models/assets'
import type { AssetLedgerItem, AssetLedgerPage, AssetLedgerQuery, LedgerGroup } from '../models/ledger'
import EquipmentRuntimePassport from '../components/EquipmentRuntimePassport.vue'
import EquipmentPointPage from './EquipmentLedgerPage.vue'

const navigation = vi.hoisted(() => ({ path: '/operations/devices/businessDevices', query: {} as Record<string, string>, push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => navigation, useRouter: () => ({ push: navigation.push }) }))
vi.mock('../api/assets', () => ({
  createBuilding: vi.fn(), createEquipment: vi.fn(), createSpace: vi.fn(), createSystemGroup: vi.fn(),
  deleteBuilding: vi.fn(), deleteEquipment: vi.fn(), deleteEquipmentPoint: vi.fn(), deleteSpace: vi.fn(), deleteSystemGroup: vi.fn(),
  getBuilding: vi.fn(), getEquipment: vi.fn(), getEquipmentReadings: vi.fn(), getEquipmentTrendHistory: vi.fn(),
  listAssetLedger: vi.fn(), listBuildings: vi.fn(), listEquipment: vi.fn(), listEquipmentPoints: vi.fn(), listSpaces: vi.fn(), listSystemGroups: vi.fn(),
  updateBuilding: vi.fn(), updateEquipment: vi.fn(), updateEquipmentPoint: vi.fn(), updateSpace: vi.fn(), updateSystemGroup: vi.fn(),
}))
vi.mock('../api/meter-coverage', () => ({ getMeterCoverage: vi.fn(), listMeterCoverageCandidates: vi.fn(), listMeterCoverages: vi.fn(), listMeterCoverageHistory: vi.fn(), updateMeterCoverage: vi.fn() }))

const eventTime = 1_700_000_000_000

function equipment(id: string, category = 'INDOOR_UNIT', buildingId = 'B1'): AssetEquipmentDetail {
  return { equipmentId: id, equipmentCode: id, equipmentName: id === 'E1' ? '301内机' : id, typeCode: category === 'ELECTRIC_METER' ? 'ELECTRIC_METER_3P' : 'IDU', category, buildingId, buildingName: buildingId, spaceId: 'S1', spaceName: '301办公室', systemGroupId: 'G1', systemGroupName: '正式系统', productId: null, productName: '正式产品', manufacturer: null, status: 'ACTIVE', expectedProfileCode: null, lastDiscoveredTime: null, pointSummary: { total: 2, required: 0, configuredRequired: 0 }, allowedActions: [], updateTime: 0, identities: [], references: {}, ratedCapacity: null, ratedPower: 0, designCop: null, parameterGovernanceStatus: null } as AssetEquipmentDetail
}

function reading(id: string, value: number | null, usageStatus = 'AVAILABLE'): AssetPointReading {
  return { pointId: id, pointCode: id, pointName: id === 'P1' ? '室内温度' : '阻断测点', unit: '°C', value, eventTime, receivedTime: eventTime + 1_000, dataQuality: usageStatus === 'AVAILABLE' ? 0 : 1, status: 'HAS_DATA', usageStatus, reason: usageStatus === 'AVAILABLE' ? 'QUALITY_ALLOWED' : 'QUALITY_NOT_ALLOWED' }
}

function ledgerItem(row: AssetEquipmentDetail, professionalGroup: LedgerGroup, displayStatus: AssetLedgerItem['displayPoint']['status'] = 'AVAILABLE'): AssetLedgerItem {
  const meter = row.category === 'ELECTRIC_METER'
  return {
    equipment: row,
    professionalGroup,
    classificationSource: 'PROFESSIONAL_RULE',
    ruleRevision: 4,
    classificationRevision: 2,
    operation: meter
      ? { applicable: false, value: 'NOT_APPLICABLE', reason: 'METER', source: null, observedAt: null, scope: 'DEVICE' }
      : { applicable: true, value: professionalGroup === 'HVAC' ? 'RUNNING' : 'UNKNOWN', reason: 'DEVICE_STATE', source: 'DEVICE_STATE', observedAt: eventTime, scope: professionalGroup === 'HVAC' ? 'COMPRESSOR' : 'DEVICE' },
    communication: meter
      ? { applicable: false, value: 'UNKNOWN', reason: 'NOT_MONITORED', source: null, observedAt: null, scope: 'DEVICE' }
      : professionalGroup === 'RENEWABLE'
        ? { applicable: true, value: 'OFFLINE', reason: 'DEVICE_TIMEOUT', source: 'DEVICE_HEARTBEAT', observedAt: eventTime, scope: 'DEVICE' }
        : { applicable: true, value: 'ONLINE', reason: 'REPORT_DELAYED', source: 'DEVICE_TELEMETRY', observedAt: eventTime, scope: 'DEVICE' },
    displayPoint: displayStatus === 'AVAILABLE'
      ? { status: 'AVAILABLE', reason: null, pointId: 'P1', pointCode: 'P1', pointName: '室内温度', unit: '°C', value: 0, eventTime, receivedTime: eventTime + 1_000, dataQuality: 0, usageStatus: 'AVAILABLE' }
      : { status: displayStatus, reason: 'NO_DEFAULT_DISPLAY_POINT', pointId: null, pointCode: null, pointName: null, unit: null, value: null, eventTime: null, receivedTime: null, dataQuality: null, usageStatus: null },
  }
}

let wrapper: ReturnType<typeof mount>
let rows: AssetEquipmentDetail[]
let ledgerRows: AssetLedgerItem[]
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
const scrollIntoView = vi.fn()
const submit = async () => { await wrapper.get('form.ledger-search').trigger('submit'); await flushPromises() }
async function chooseBuilding(value: string) {
  const select = wrapper.findAllComponents(ElSelect)[0]!
  select.vm.$emit('update:modelValue', value)
  select.vm.$emit('change', value)
  await flushPromises()
}

function ledgerPage(query: AssetLedgerQuery): AssetLedgerPage {
  const scoped = ledgerRows.filter(item =>
    (!query.buildingId || item.equipment.buildingId === query.buildingId)
    && (!query.keyword || item.equipment.equipmentName.includes(query.keyword))
    && (!query.spaceId || item.equipment.spaceId === query.spaceId),
  )
  const items = query.professionalGroup === 'ALL' ? scoped : scoped.filter(item => item.professionalGroup === query.professionalGroup)
  const groupCounts = Object.fromEntries(['ALL', 'POWER', 'HVAC', 'RENEWABLE', 'WATER', 'ENVIRONMENT', 'UNCLASSIFIED'].map(group => [group, group === 'ALL' ? scoped.length : scoped.filter(item => item.professionalGroup === group).length])) as AssetLedgerPage['groupCounts']
  return {
    page: query.page,
    size: query.size,
    total: items.length,
    items: items.slice((query.page - 1) * query.size, query.page * query.size),
    groupCounts,
    summary: {
      assets: scoped.length,
      points: scoped.reduce((total, item) => total + item.equipment.pointSummary.total, 0),
      online: scoped.filter(item => item.communication.applicable && item.communication.value === 'ONLINE').length,
      offline: scoped.filter(item => item.communication.applicable && item.communication.value === 'OFFLINE').length,
      communicationError: 0,
      communicationUnknown: scoped.filter(item => item.communication.applicable && item.communication.value === 'UNKNOWN').length,
      notMonitored: scoped.filter(item => !item.communication.applicable).length,
      delayed: scoped.filter(item => item.communication.applicable && item.communication.value === 'ONLINE' && item.communication.reason === 'REPORT_DELAYED').length,
    },
    generatedAt: eventTime,
  }
}

beforeEach(async () => {
  vi.resetAllMocks()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })
  navigation.path = '/operations/devices/businessDevices'
  navigation.query = {}
  rows = [equipment('E1'), equipment('M1', 'ELECTRIC_METER'), equipment('U1', 'CUSTOM_TYPE')]
  ledgerRows = [ledgerItem(rows[0]!, 'HVAC'), ledgerItem(rows[1]!, 'POWER', 'NO_DATA'), ledgerItem(rows[2]!, 'RENEWABLE', 'UNCONFIGURED')]
  vi.mocked(assets.listAssetLedger).mockImplementation(async query => ledgerPage(query))
  vi.mocked(assets.listBuildings).mockResolvedValue({ page: 1, size: 100, total: 2, items: [{ buildingId: 'B1', buildingName: '建筑一' }, { buildingId: 'B2', buildingName: '建筑二' }] as never })
  vi.mocked(assets.listSpaces).mockImplementation(async buildingId => [{ spaceId: 'F3', buildingId, spaceName: '三层', children: [{ spaceId: 'S1', buildingId, spaceName: '301办公室', children: [] }] }] as never)
  vi.mocked(assets.listSystemGroups).mockResolvedValue({ page: 1, size: 100, total: 1, items: [{ systemGroupId: 'G1', systemName: '正式系统' }] as never })
  vi.mocked(assets.getEquipment).mockImplementation(async id => rows.find(row => row.equipmentId === id)!)
  vi.mocked(assets.listEquipmentPoints).mockResolvedValue([])
  vi.mocked(assets.getEquipmentReadings).mockImplementation(async id => ({ equipmentId: id, buildingId: rows.find(row => row.equipmentId === id)!.buildingId, generatedAt: eventTime + 1_000, points: [reading('P1', 0), reading('P2', 999, 'QUALITY_BLOCKED')] }))
  vi.mocked(assets.getEquipmentTrendHistory).mockImplementation(async (id, query) => ({ equipmentId: id, buildingId: 'B1', startTime: query.startTime, endTime: query.endTime, series: [{ pointCode: 'P1', pointName: '室内温度', unit: '°C', data: [[eventTime, 0]] }] }))
  vi.mocked(listMeterCoverages).mockResolvedValue([])
  vi.mocked(getMeterCoverage).mockResolvedValue({ equipmentId: 'M1', revision: 0, targets: [], quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY' } as never)
  vi.mocked(listMeterCoverageCandidates).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
  vi.mocked(listMeterCoverageHistory).mockResolvedValue({ page: 1, size: 100, total: 0, items: [] })
  wrapper = mount(EquipmentPointPage, { global: { directives: { loading: {} }, stubs: { ChartView: { template: '<div data-test="chart-stub" />' } } } })
  await flushPromises()
})
afterEach(() => {
  wrapper.unmount()
  if (originalScroll) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll)
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('原型设备综合台账', () => {
  it('使用后端分页聚合，不拉取旧设备列表或逐行补读数来推断台账', () => {
    expect(wrapper.get('h1').text()).toContain('设备综合台账')
    expect(wrapper.findAll('.ledger-categories button')).toHaveLength(7)
    expect(assets.listAssetLedger).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20, buildingId: 'B1', professionalGroup: 'ALL' }))
    expect(assets.listEquipment).not.toHaveBeenCalled()
    expect(assets.getEquipmentReadings).toHaveBeenCalledWith('E1')
    expect(assets.getEquipmentReadings).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.ledger-summary').text()).toContain('设备在线1台')
    expect(wrapper.get('.ledger-summary').text()).toContain('含上报延迟 1 台')
    expect(wrapper.get('.ledger-summary').text()).toContain('设备离线1台')
    expect(wrapper.get('.ledger-summary').text()).toContain('通讯未知0台')
    expect(wrapper.get('.ledger-summary').text()).toContain('未监测1台')
    expect(wrapper.get('.ledger-summary').text()).not.toContain('通讯异常')
    expect(wrapper.get('.equipment-table').text()).toContain('通讯：未监测')
    expect(wrapper.text()).not.toContain('NOT_MONITORED')
    expect(wrapper.get('.equipment-table').text()).toContain('压缩机开启')
    expect(wrapper.get('.equipment-table').text()).toContain('默认展示测点')
  })

  it('七个专业标签使用后端计数；原先待确认的三个专业可真实请求筛选', async () => {
    expect(wrapper.findAll('.ledger-categories button').find(button => button.text().includes('可再生能源'))?.text()).toContain('(1)')
    for (const group of ['RENEWABLE', 'WATER', 'ENVIRONMENT'] as const) {
      await wrapper.find(`.ledger-categories button[data-group="${group}"]`).trigger('click')
      await flushPromises()
      expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ professionalGroup: group, page: 1, size: 20 }))
    }
    await wrapper.find('.ledger-categories button[data-group="RENEWABLE"]').trigger('click')
    await flushPromises()
    const renewableTable = wrapper.get('.equipment-table')
    expect(renewableTable.text()).toContain('U1')
    expect(renewableTable.text()).not.toContain('301内机')
    expect(renewableTable.text()).toContain('未配置展示测点')
    expect(renewableTable.text()).not.toContain('NO_DEFAULT_DISPLAY_POINT')
    expect(renewableTable.findAll('strong').map(node => node.text())).toContain('—')
  })

  it('中间名称搜索和空间选择提交聚合查询，左侧空间查找不触发业务请求', async () => {
    const requests = vi.mocked(assets.listAssetLedger).mock.calls.length
    await wrapper.get('.space-search-label input').setValue('301')
    await flushPromises()
    expect(assets.listAssetLedger).toHaveBeenCalledTimes(requests)
    wrapper.findComponent(ElTree).vm.$emit('node-click', { spaceId: 'S1' }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ buildingId: 'B1', spaceId: 'S1', page: 1, size: 20 }))
    await wrapper.get('.ledger-search input').setValue('301')
    await submit()
    expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '301', spaceId: 'S1', professionalGroup: 'ALL' }))
  })

  it('楼层交给后端汇总，整栋建筑入口请求建筑下的全部设备', async () => {
    const tree = wrapper.findComponent(ElTree)
    tree.vm.$emit('node-click', { spaceId: 'F3', spaceType: 'FLOOR' }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ buildingId: 'B1', spaceId: 'F3' }))

    const roots = tree.props('data')
    expect(roots).toHaveLength(1)
    expect(roots[0]).toMatchObject({ key: 'building:B1', label: '建筑一', children: [{ key: 'space:F3', spaceId: 'F3' }] })
    await tree.findAll('.el-tree-node__content')[0]!.trigger('click')
    await flushPromises()
    expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ buildingId: 'B1', spaceId: undefined }))
    expect(wrapper.get('[data-group="ALL"]').text()).toContain('(3)')

    tree.vm.$emit('node-click', { spaceId: 'F3', spaceType: 'FLOOR' }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    tree.vm.$emit('node-click', { key: 'space:S_B1', spaceId: 'S_B1', label: '建筑一' }, {}, {}, new MouseEvent('click'))
    await flushPromises()
    expect(assets.listAssetLedger).toHaveBeenLastCalledWith(expect.objectContaining({ buildingId: 'B1', spaceId: 'S_B1' }))
  })

  it('列表始终使用后端 displayPoint；右栏切换真实测点不会改写主表', async () => {
    const passport = wrapper.findComponent(EquipmentRuntimePassport)
    expect(passport.props('selectedPointId')).toBe('P1')
    expect(assets.getEquipmentTrendHistory).toHaveBeenCalledWith('E1', expect.objectContaining({ intervalSeconds: 60 }))
    expect(wrapper.get('.equipment-table').text()).toContain('室内温度')
    expect(wrapper.get('.equipment-table').text()).toContain('0 °C')
    passport.vm.$emit('selectPoint', 'P2')
    await flushPromises()
    expect(wrapper.get('.equipment-table').text()).toContain('室内温度')
    expect(wrapper.get('.equipment-table').text()).toContain('0 °C')
    expect(wrapper.get('.equipment-table').text()).not.toContain('999')
  })

  it('电表默认功率与实时卡片共用瓦和读数精度', async () => {
    const meter = ledgerItem(rows[1]!, 'POWER')
    const point = { ...reading('P1', 0.022), pointName: '输入功率', unit: 'kW' }
    ledgerRows = [{ ...meter, displayPoint: { ...meter.displayPoint, ...point, status: 'AVAILABLE' } }]
    vi.mocked(assets.getEquipmentReadings).mockResolvedValue({
      equipmentId: 'M1', buildingId: 'B1', generatedAt: eventTime, points: [point],
    })
    await wrapper.get('.page-heading button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.equipment-table').text()).toContain('22 W')
    expect(wrapper.findComponent(EquipmentRuntimePassport).get('[data-test="reading-value"]').text()).toContain('22 W')
  })

  it('建筑切换和聚合失败会撤下旧详情，不用旧分类或假数据回退', async () => {
    expect(wrapper.findComponent(EquipmentRuntimePassport).props('equipment').equipmentId).toBe('E1')
    rows = [equipment('E2', 'INDOOR_UNIT', 'B2')]
    ledgerRows = [ledgerItem(rows[0]!, 'HVAC')]
    await chooseBuilding('B2')
    expect(wrapper.findComponent(EquipmentRuntimePassport).props('equipment').equipmentId).toBe('E2')
    expect(wrapper.get('.equipment-table').text()).not.toContain('301内机')
    vi.mocked(assets.listAssetLedger).mockRejectedValueOnce(new Error('offline'))
    await submit()
    expect(wrapper.get('.ledger-center').text()).toContain('请求失败')
    expect(wrapper.findComponent(EquipmentRuntimePassport).props('equipment')).toBeNull()
  })

  it('在可见页面更新雷达当前值，保留历史和选中设备，离开雷达列表后停止刷新', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const unit = { ...equipment('RADAR1'), typeCode: 'RADAR_SENSOR' }
    const sensor = { kind: 'RADAR' as const, sourceId: 'RADAR-SOURCE', currentCount: 0, lastObservedCount: 0, lastObservedAt: eventTime }
    const item = { ...ledgerItem(unit, 'ENVIRONMENT', 'UNCONFIGURED'), roomSensor: sensor }
    rows.push(unit)
    ledgerRows.unshift(item)
    let snapshotAt = eventTime
    vi.mocked(assets.listAssetLedger).mockImplementation(async query => ({ ...ledgerPage(query), generatedAt: snapshotAt }))
    vi.mocked(assets.getEquipmentReadings).mockResolvedValue({ equipmentId: unit.equipmentId, buildingId: 'B1', generatedAt: eventTime + 1_000, points: [], roomSensors: [sensor] })
    await wrapper.get('[data-group="ENVIRONMENT"]').trigger('click')
    await flushPromises()
    const table = wrapper.get('.equipment-table')
    const reading = () => wrapper.get('[data-test="room-sensor-value"]').text()
    expect(table.text()).toContain('0 人')
    expect(reading()).toBe('0 人')
    vi.mocked(assets.getEquipment).mockClear()
    vi.mocked(assets.getEquipmentReadings).mockClear()
    vi.mocked(assets.getEquipmentTrendHistory).mockClear()
    ledgerRows[0] = { ...item, roomSensor: { ...sensor, currentCount: null } }
    snapshotAt = eventTime + 10_000
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(table.text()).toContain('最后有效观测 0 人')
    expect(reading()).toBe('未知')
    expect(assets.getEquipment).not.toHaveBeenCalled()
    expect(assets.getEquipmentReadings).not.toHaveBeenCalled()
    expect(assets.getEquipmentTrendHistory).not.toHaveBeenCalled()
    ledgerRows[0] = { ...item, roomSensor: { ...sensor, currentCount: 1, lastObservedCount: 1, lastObservedAt: snapshotAt } }
    await vi.advanceTimersByTimeAsync(5_000)
    expect(reading()).toBe('1 人')
    vi.mocked(assets.listAssetLedger).mockRejectedValueOnce(new Error('network'))
    await vi.advanceTimersByTimeAsync(5_000)
    expect(reading()).toBe('未知')
    expect(wrapper.get('.ledger-summary').text()).toContain('读数自动刷新暂不可用')
    await vi.advanceTimersByTimeAsync(5_000)
    expect(reading()).toBe('1 人')
    await wrapper.get('[data-group="HVAC"]').trigger('click')
    await flushPromises()
    const requests = vi.mocked(assets.listAssetLedger).mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(assets.listAssetLedger).toHaveBeenCalledTimes(requests)
  })

  it('重新展开时定位到设备详情，完整设备护照和电表档案仍可使用', async () => {
    wrapper.findComponent(EquipmentRuntimePassport).vm.$emit('collapse')
    await flushPromises()
    expect(wrapper.get('.ledger-layout').classes()).toContain('is-collapsed')
    expect(wrapper.find('.ledger-passport').exists()).toBe(false)
    await wrapper.get('.table-navigation button').trigger('click')
    await flushPromises()
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
    expect(scrollIntoView.mock.contexts[0]).toBe(wrapper.get('.ledger-passport').element)
    wrapper.findComponent(EquipmentRuntimePassport).vm.$emit('archive')
    await flushPromises()
    expect(wrapper.findComponent(ElTabs).props('modelValue')).toBe('archive')
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(expect.arrayContaining(['设备测点', '接入信息', '技术参数']))
    wrapper.unmount()
    navigation.path = '/operations/devices/meters'
    wrapper = mount(EquipmentPointPage, { global: { directives: { loading: {} }, stubs: { ChartView: { template: '<div />' } } } })
    await flushPromises()
    expect(wrapper.get('.ledger-categories .active').text()).toContain('电力与计量')
    expect(wrapper.get('.equipment-table').text()).toContain('M1')
    wrapper.findComponent(EquipmentRuntimePassport).vm.$emit('archive')
    await flushPromises()
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toContain('电表档案')
    expect(wrapper.findAllComponents(ElSelect)[0]!.props('modelValue')).toBe('B1')
  })
})
