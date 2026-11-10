import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestApi = vi.hoisted(() => vi.fn())
vi.mock('@/infrastructure/http/public', () => ({ requestApi }))

import { alarmsApi } from './alarms'

describe('alarmsApi', () => {
  beforeEach(() => requestApi.mockReset())

  it('requests summary and paged category events using the agreed contract', async () => {
    requestApi.mockResolvedValueOnce({ deviceAnomalies: 2 }).mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 20 })
    await alarmsApi.summary('B/1')
    await alarmsApi.events('B/1', { history: false, type: 'EMISSION', page: 1, size: 20, state: 'UNACK', alarmType: 'QUOTA', keyword: '配额' })
    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/alarms/buildings/B%2F1/summary' })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'get', url: '/v1/alarms/buildings/B%2F1/events', params: { history: false, type: 'EMISSION', page: 1, size: 20, state: 'UNACK', alarmType: 'QUOTA', keyword: '配额' } })
  })

  it('loads only alarm-authorized buildings for the active view', async () => {
    requestApi.mockResolvedValueOnce([])
    await alarmsApi.buildings('history')
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/alarms/buildings', params: { page: 'history' } })
  })

  it('uses recovered-only history plus a half-open millisecond range', async () => {
    requestApi.mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 20 })
    await alarmsApi.events('B1', { history: true, type: 'DEVICE', page: 1, size: 20, state: 'UNACK', fromAt: 1000, toAt: 2000 })
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/alarms/buildings/B1/events', params: { history: true, type: 'DEVICE', page: 1, size: 20, fromAt: 1000, toAt: 2000 } })
  })

  it('writes rule revision and action bodies without adding client-side state fields', async () => {
    requestApi.mockResolvedValue(undefined)
    await alarmsApi.updateRule('B1', { type: 'QUOTA', enabled: true, parameter: 80, revision: 4 })
    await alarmsApi.ruleHistory('B1', 'OFFLINE')
    await alarmsApi.acknowledge('B1', 'c6494f7c-341b-4651-8ef6-0a3b4a92315c')
    await alarmsApi.note('B1', 'c6494f7c-341b-4651-8ef6-0a3b4a92315c', '复核中')
    await alarmsApi.silence('B1', 'c6494f7c-341b-4651-8ef6-0a3b4a92315c', '检修时段')
    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'put', url: '/v1/alarms/buildings/B1/rules/QUOTA', data: { type: 'QUOTA', enabled: true, parameter: 80, revision: 4, spaceId: null, deviceCategory: null } })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'get', url: '/v1/alarms/buildings/B1/rules/OFFLINE/history' })
    expect(requestApi).toHaveBeenNthCalledWith(3, { method: 'post', url: '/v1/alarms/buildings/B1/events/c6494f7c-341b-4651-8ef6-0a3b4a92315c/ack', data: {} })
    expect(requestApi).toHaveBeenNthCalledWith(4, { method: 'post', url: '/v1/alarms/buildings/B1/events/c6494f7c-341b-4651-8ef6-0a3b4a92315c/note', data: { note: '复核中' } })
    expect(requestApi).toHaveBeenNthCalledWith(5, { method: 'post', url: '/v1/alarms/buildings/B1/events/c6494f7c-341b-4651-8ef6-0a3b4a92315c/silence', data: { minutes: 60, note: '检修时段' } })
  })
  it('sends condition groups and recovery settings through the public API', async () => {
    const rule = { type: 'CONDITION' as const, name: '停机耗电', severity: 'IMPORTANT' as const, qualityLevel: 'Q1' as const,
      enabled: true, parameter: 1, revision: 0, classification: 'FAULT' as const, recoveryCycles: 2, triggerMinutes: 15, recoveryMinutes: 10, conditionLogic: 'ALL' as const,
      conditions: [{ pointId: 'power', operator: 'GT' as const, value: 5 }, { pointId: 'state', operator: 'EQ' as const, value: 7, states: [{ value: 7, label: '关机' }, { value: 9, label: '开机' }] }] }
    await alarmsApi.createRule('B1', rule)
    expect(requestApi).toHaveBeenCalledWith({ method: 'post', url: '/v1/alarms/buildings/B1/rules', data: { ...rule, spaceId: null, deviceCategory: null } })
  })

})
