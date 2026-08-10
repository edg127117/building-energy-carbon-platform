import type { HvacRealtimeIndicator } from '@/types/hvac'
import {
  classifyHvacRealtimeClose,
  HvacRealtimeProtocolError,
  parseHvacRealtimeServerMessage,
  resolveHvacRealtimeUrl,
  type HvacRealtimeDisconnect,
} from './hvacRealtimeProtocol'

export type HvacRealtimeSocket = {
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent<string>) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  onerror: ((event: Event) => void) | null
  send: (payload: string) => void
  close: (code?: number) => void
}

export type HvacRealtimeClientEvents = {
  onSubscribed?: (buildingId: string) => void
  onIndicator?: (indicator: HvacRealtimeIndicator) => void
  onServerError?: (error: { code: string; message: string }) => void
  onDisconnected?: (disconnect: HvacRealtimeDisconnect) => void
}

export interface HvacRealtimeClient {
  connect(input: { token: string; buildingId: string }): void
  close(): void
}

// 浏览器运行时的 Window 定时器返回数字；不使用 Node 的 Timeout 类型以保持客户端可替换性。
type TimerHandle = number

type HvacRealtimeTimers = {
  setTimeout: (handler: () => void, delay: number) => TimerHandle
  clearTimeout: (timer: TimerHandle) => void
  setInterval: (handler: () => void, delay: number) => TimerHandle
  clearInterval: (timer: TimerHandle) => void
}

export type HvacRealtimeClientOptions = {
  events: HvacRealtimeClientEvents
  urlResolver?: () => string
  socketFactory?: (url: string) => HvacRealtimeSocket
  timers?: Partial<HvacRealtimeTimers>
}

type ActiveConnection = {
  socket: HvacRealtimeSocket
  buildingId: string
  subscribed: boolean
  reportedDisconnect: boolean
  subscribeTimer: TimerHandle | null
  pingTimer: TimerHandle | null
  pongTimer: TimerHandle | null
}

const SUBSCRIBE_TIMEOUT_MS = 5_000
const PING_INTERVAL_MS = 20_000
const PONG_TIMEOUT_MS = 10_000

const browserTimers: HvacRealtimeTimers = {
  setTimeout: (handler, delay) => window.setTimeout(handler, delay),
  clearTimeout: (timer) => window.clearTimeout(timer),
  setInterval: (handler, delay) => window.setInterval(handler, delay),
  clearInterval: (timer) => window.clearInterval(timer),
}

function browserSocketFactory(url: string): HvacRealtimeSocket {
  return new WebSocket(url) as unknown as HvacRealtimeSocket
}

/**
 * 创建一个仅管理单条 WebSocket 生命周期的客户端。
 *
 * 它负责升级后的 JWT 首帧、订阅/PING/PONG 时限和旧 Socket 事件隔离；HTTP 对账、轮询与
 * 重连归 Dashboard Composable 所有，避免底层传输层绕过建筑状态或发起并行数据请求。
 */
export function createHvacRealtimeClient(
  options: HvacRealtimeClientOptions,
): HvacRealtimeClient {
  const timers: HvacRealtimeTimers = { ...browserTimers, ...options.timers }
  const socketFactory = options.socketFactory ?? browserSocketFactory
  const urlResolver = options.urlResolver ?? resolveHvacRealtimeUrl
  let active: ActiveConnection | null = null

  function isActive(connection: ActiveConnection): boolean {
    return active === connection
  }

  function clearTimers(connection: ActiveConnection): void {
    if (connection.subscribeTimer !== null) {
      timers.clearTimeout(connection.subscribeTimer)
      connection.subscribeTimer = null
    }
    if (connection.pingTimer !== null) {
      timers.clearInterval(connection.pingTimer)
      connection.pingTimer = null
    }
    if (connection.pongTimer !== null) {
      timers.clearTimeout(connection.pongTimer)
      connection.pongTimer = null
    }
  }

  function disconnect(
    connection: ActiveConnection,
    result: HvacRealtimeDisconnect,
    closeCode?: number,
  ): void {
    if (!isActive(connection) || connection.reportedDisconnect) return
    connection.reportedDisconnect = true
    clearTimers(connection)
    active = null
    try {
      if (closeCode === undefined) connection.socket.close()
      else connection.socket.close(closeCode)
    } catch {
      // 浏览器已关闭或网络栈已释放时，仍必须通知上层启动 HTTP 保障。
    }
    options.events.onDisconnected?.(result)
  }

  function disconnectForProtocol(
    connection: ActiveConnection,
  ): void {
    disconnect(connection, classifyHvacRealtimeClose(4400), 4400)
  }

  function sendPing(connection: ActiveConnection): void {
    if (!isActive(connection) || !connection.subscribed) return
    try {
      connection.socket.send(JSON.stringify({ type: 'PING' }))
    } catch {
      disconnect(connection, classifyHvacRealtimeClose(1006))
      return
    }

    if (connection.pongTimer !== null) return
    connection.pongTimer = timers.setTimeout(() => {
      if (!isActive(connection)) return
      disconnect(connection, classifyHvacRealtimeClose(4408), 4408)
    }, PONG_TIMEOUT_MS)
  }

  function startHeartbeat(connection: ActiveConnection): void {
    if (connection.pingTimer !== null) return
    connection.pingTimer = timers.setInterval(
      () => sendPing(connection),
      PING_INTERVAL_MS,
    )
  }

  function attachSocketHandlers(
    connection: ActiveConnection,
    token: string,
  ): void {
    connection.socket.onopen = () => {
      if (!isActive(connection)) return
      try {
        connection.socket.send(JSON.stringify({
          type: 'SUBSCRIBE',
          token,
          buildingId: connection.buildingId,
        }))
      } catch {
        disconnect(connection, classifyHvacRealtimeClose(1006))
        return
      }
      connection.subscribeTimer = timers.setTimeout(() => {
        if (!isActive(connection) || connection.subscribed) return
        disconnect(connection, classifyHvacRealtimeClose(4408), 4408)
      }, SUBSCRIBE_TIMEOUT_MS)
    }

    connection.socket.onmessage = (event) => {
      if (!isActive(connection) || typeof event.data !== 'string') {
        if (isActive(connection)) disconnectForProtocol(connection)
        return
      }

      try {
        const message = parseHvacRealtimeServerMessage(event.data)
        switch (message.type) {
          case 'SUBSCRIBED':
            if (message.buildingId !== connection.buildingId) {
              disconnectForProtocol(connection)
              return
            }
            if (connection.subscribed) return
            connection.subscribed = true
            if (connection.subscribeTimer !== null) {
              timers.clearTimeout(connection.subscribeTimer)
              connection.subscribeTimer = null
            }
            startHeartbeat(connection)
            options.events.onSubscribed?.(message.buildingId)
            return
          case 'PONG':
            if (!connection.subscribed) {
              disconnectForProtocol(connection)
              return
            }
            if (connection.pongTimer !== null) {
              timers.clearTimeout(connection.pongTimer)
              connection.pongTimer = null
            }
            return
          case 'HVAC_INDICATOR':
            if (!connection.subscribed) {
              disconnectForProtocol(connection)
              return
            }
            options.events.onIndicator?.(message.data)
            return
          case 'ERROR':
            options.events.onServerError?.({
              code: message.code,
              message: message.message,
            })
            return
        }
      } catch (error) {
        if (error instanceof HvacRealtimeProtocolError) {
          disconnectForProtocol(connection)
          return
        }
        disconnect(connection, classifyHvacRealtimeClose(1006))
      }
    }

    connection.socket.onclose = (event) => {
      if (!isActive(connection)) return
      disconnect(connection, classifyHvacRealtimeClose(event.code))
    }

    connection.socket.onerror = () => {
      if (!isActive(connection)) return
      disconnect(connection, classifyHvacRealtimeClose(1006))
    }
  }

  function close(): void {
    const connection = active
    if (!connection) return
    clearTimers(connection)
    active = null
    try {
      connection.socket.close(1000)
    } catch {
      // 主动卸载不应因浏览器已关闭的底层 Socket 影响页面清理。
    }
  }

  function connect(input: { token: string; buildingId: string }): void {
    close()
    const socket = socketFactory(urlResolver())
    const connection: ActiveConnection = {
      socket,
      buildingId: input.buildingId,
      subscribed: false,
      reportedDisconnect: false,
      subscribeTimer: null,
      pingTimer: null,
      pongTimer: null,
    }
    active = connection
    attachSocketHandlers(connection, input.token)
  }

  return { connect, close }
}
