import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createHvacRealtimeClient,
  type HvacRealtimeClientEvents,
  type HvacRealtimeSocket,
} from './hvacRealtimeClient'

class FakeSocket implements HvacRealtimeSocket {
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  readonly sent: string[] = []
  readonly close = vi.fn()

  send(payload: string): void {
    this.sent.push(payload)
  }

  open(): void {
    this.onopen?.(new Event('open'))
  }

  message(payload: unknown): void {
    this.onmessage?.({ data: payload } as MessageEvent<string>)
  }

  closeFromServer(code: number): void {
    this.onclose?.({ code } as CloseEvent)
  }
}

function createHarness() {
  const sockets: FakeSocket[] = []
  const events: Required<Pick<
    HvacRealtimeClientEvents,
    'onSubscribed' | 'onIndicator' | 'onDisconnected' | 'onServerError'
  >> = {
    onSubscribed: vi.fn(),
    onIndicator: vi.fn(),
    onDisconnected: vi.fn(),
    onServerError: vi.fn(),
  }
  const client = createHvacRealtimeClient({
    events,
    urlResolver: () => 'ws://localhost:8081/api/ws/hvac',
    socketFactory: () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    },
  })
  return { client, sockets, events }
}

const indicatorMessage = JSON.stringify({
  type: 'HVAC_INDICATOR',
  data: {
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
  },
})

describe('HvacRealtimeClient', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('sends one post-upgrade subscription and waits no longer than five seconds', () => {
    const { client, sockets, events } = createHarness()
    client.connect({ token: 'jwt-value', buildingId: 'BLD001' })
    sockets[0].open()

    expect(sockets[0].sent).toEqual([
      JSON.stringify({ type: 'SUBSCRIBE', token: 'jwt-value', buildingId: 'BLD001' }),
    ])
    expect(sockets[0].sent[0]).not.toContain('ws/hvac?')

    vi.advanceTimersByTime(5_000)

    expect(events.onDisconnected).toHaveBeenCalledWith(
      expect.objectContaining({ code: 4408, kind: 'timeout', retry: true }),
    )
    expect(sockets[0].close).toHaveBeenCalledWith(4408)
  })

  it('starts heartbeat only after subscription and requires a PONG within ten seconds', () => {
    const { client, sockets, events } = createHarness()
    client.connect({ token: 'jwt-value', buildingId: 'BLD001' })
    sockets[0].open()
    sockets[0].message(JSON.stringify({ type: 'SUBSCRIBED', buildingId: 'BLD001', serverTime: 1 }))

    expect(events.onSubscribed).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(20_000)
    expect(sockets[0].sent).toContain(JSON.stringify({ type: 'PING' }))
    sockets[0].message(JSON.stringify({ type: 'PONG', serverTime: 2 }))
    vi.advanceTimersByTime(20_000)
    expect(sockets[0].sent.filter((payload) => payload === JSON.stringify({ type: 'PING' }))).toHaveLength(2)
    vi.advanceTimersByTime(9_999)
    expect(events.onDisconnected).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(events.onDisconnected).toHaveBeenCalledWith(
      expect.objectContaining({ code: 4408, kind: 'timeout' }),
    )
  })

  it('forwards complete indicator frames and exact server close classifications', () => {
    const { client, sockets, events } = createHarness()
    client.connect({ token: 'jwt-value', buildingId: 'BLD001' })
    sockets[0].open()
    sockets[0].message(JSON.stringify({ type: 'SUBSCRIBED', buildingId: 'BLD001', serverTime: 1 }))
    sockets[0].message(indicatorMessage)
    sockets[0].message(JSON.stringify({ type: 'ERROR', code: 'TEMPORARY', message: '实时服务暂不可用' }))
    sockets[0].closeFromServer(1011)

    expect(events.onIndicator).toHaveBeenCalledWith(
      expect.objectContaining({ indicatorCode: 'WCR_COP', buildingId: 'BLD001' }),
    )
    expect(events.onServerError).toHaveBeenCalledWith({
      code: 'TEMPORARY',
      message: '实时服务暂不可用',
    })
    expect(events.onDisconnected).toHaveBeenCalledWith(
      expect.objectContaining({ code: 1011, kind: 'server_error', retry: true }),
    )
  })

  it.each([4400, 4401, 4403, 4408, 1011])('classifies application close code %i', (code) => {
    const { client, sockets, events } = createHarness()
    client.connect({ token: 'jwt-value', buildingId: 'BLD001' })
    sockets[0].closeFromServer(code)

    expect(events.onDisconnected).toHaveBeenCalledWith(
      expect.objectContaining({ code }),
    )
  })

  it('cancels timers on deliberate close and ignores events from a replaced socket', () => {
    const { client, sockets, events } = createHarness()
    client.connect({ token: 'jwt-value', buildingId: 'BLD001' })
    const first = sockets[0]
    client.connect({ token: 'jwt-value', buildingId: 'BLD002' })
    const second = sockets[1]

    first.open()
    first.message(indicatorMessage)
    expect(first.sent).toEqual([])
    expect(events.onIndicator).not.toHaveBeenCalled()

    second.open()
    client.close()
    vi.advanceTimersByTime(60_000)
    expect(events.onDisconnected).not.toHaveBeenCalled()
    expect(second.close).toHaveBeenCalledWith(1000)
  })
})
