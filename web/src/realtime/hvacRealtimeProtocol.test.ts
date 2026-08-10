import { describe, expect, it } from 'vitest'
import {
  HvacRealtimeProtocolError,
  classifyHvacRealtimeClose,
  parseHvacRealtimeServerMessage,
  resolveHvacRealtimeUrl,
} from './hvacRealtimeProtocol'

const indicator = {
  indicatorId: 'INDICATOR_WCR_COP_BLD001',
  indicatorCode: 'WCR_COP',
  buildingId: 'BLD001',
  equipId: 'WCR1',
  minuteStart: 1786291140000,
  status: 'SUCCESS',
  value: 5.21,
  dataQuality: 0,
  formulaVersion: 'WCR_V1',
  reasonCode: null,
  missingInputs: [],
}

describe('HVAC realtime protocol', () => {
  it('parses only complete server messages for the four stable indicators', () => {
    expect(
      parseHvacRealtimeServerMessage(
        JSON.stringify({ type: 'SUBSCRIBED', buildingId: 'BLD001', serverTime: 1 }),
      ),
    ).toEqual({ type: 'SUBSCRIBED', buildingId: 'BLD001', serverTime: 1 })
    expect(
      parseHvacRealtimeServerMessage(
        JSON.stringify({ type: 'PONG', serverTime: 2 }),
      ),
    ).toEqual({ type: 'PONG', serverTime: 2 })
    expect(
      parseHvacRealtimeServerMessage(
        JSON.stringify({ type: 'HVAC_INDICATOR', data: indicator }),
      ),
    ).toEqual({ type: 'HVAC_INDICATOR', data: indicator })
    expect(
      parseHvacRealtimeServerMessage(
        JSON.stringify({ type: 'ERROR', code: 'FORBIDDEN_BUILDING', message: '无权订阅该建筑' }),
      ),
    ).toEqual({ type: 'ERROR', code: 'FORBIDDEN_BUILDING', message: '无权订阅该建筑' })
  })

  it.each([
    ['unknown message type', '{"type":"CONTROL"}'],
    ['missing subscription building', '{"type":"SUBSCRIBED"}'],
    ['unknown indicator', JSON.stringify({ type: 'HVAC_INDICATOR', data: { ...indicator, indicatorCode: 'UNKNOWN' } })],
    ['non-finite minute', JSON.stringify({ type: 'HVAC_INDICATOR', data: { ...indicator, minuteStart: null } })],
    ['invalid missing input collection', JSON.stringify({ type: 'HVAC_INDICATOR', data: { ...indicator, missingInputs: {} } })],
  ])('rejects %s without silently creating defaults', (_label, payload) => {
    expect(() => parseHvacRealtimeServerMessage(payload)).toThrow(
      HvacRealtimeProtocolError,
    )
  })

  it('uses one configured application context without credential query parameters', () => {
    expect(
      resolveHvacRealtimeUrl({
        wsBase: 'ws://localhost:8081/api?token=ignored&buildingId=ignored',
        browserOrigin: 'https://dashboard.example',
      }),
    ).toBe('ws://localhost:8081/api/ws/hvac')
    expect(
      resolveHvacRealtimeUrl({
        apiBase: 'https://gateway.example/api',
        browserOrigin: 'http://dashboard.example',
      }),
    ).toBe('wss://gateway.example/api/ws/hvac')
    expect(
      resolveHvacRealtimeUrl({
        apiBase: '/api',
        browserOrigin: 'https://dashboard.example',
      }),
    ).toBe('wss://dashboard.example/api/ws/hvac')
    expect(
      resolveHvacRealtimeUrl({ browserOrigin: 'https://dashboard.example' }),
    ).toBe('wss://dashboard.example/api/ws/hvac')
  })

  it.each([
    [4400, 'protocol', false],
    [4401, 'unauthorized', false],
    [4403, 'forbidden', false],
    [4408, 'timeout', true],
    [1011, 'server_error', true],
    [1006, 'network', true],
  ])('classifies close code %i', (code, kind, retry) => {
    expect(classifyHvacRealtimeClose(code)).toMatchObject({ code, kind, retry })
  })
})
