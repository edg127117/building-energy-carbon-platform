import { describe, expect, it, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useDeviceStore } from './device'

describe('device store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('applyWsMessage(property) should append telemetry', () => {
    const s = useDeviceStore()
    s.devices = [{ id: 1, deviceId: 'meter-1', deviceName: '电表', deviceType: 1, status: 0 }]

    s.applyWsMessage({
      deviceId: 'meter-1',
      type: 'property',
      data: { voltage_a: 220.1, current_a: 1.2, active_power: 0.38 },
      timestamp: 1000,
    })

    expect(s.telemetryByDevice['meter-1'].length).toBe(1)
    expect(s.devices[0].status).toBe(1)
  })

  it('applyWsMessage(offline) should add alarm and set offline', () => {
    const s = useDeviceStore()
    s.devices = [{ id: 1, deviceId: 'meter-1', deviceName: '电表', deviceType: 1, status: 1 }]

    s.applyWsMessage({
      deviceId: 'meter-1',
      type: 'offline',
      timestamp: 2000,
    })

    expect(s.devices[0].status).toBe(0)
    expect(s.alarms.length).toBe(1)
  })
})

