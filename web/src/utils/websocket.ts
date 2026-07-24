import type { WsDeviceMessage } from '@/types/ws'

type Status = 'idle' | 'connecting' | 'open' | 'closed' | 'error'

type Options = {
  onMessage: (msg: WsDeviceMessage) => void
  onStatus?: (s: Status) => void
}

function resolveWsUrl() {
  const explicit = import.meta.env.VITE_WS_URL
  if (explicit) return explicit

  const apiBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8081/api'
  const origin = apiBase.replace(/\/api\/?$/, '')
  const wsOrigin = origin.startsWith('https://') ? origin.replace('https://', 'wss://') : origin.replace('http://', 'ws://')
  return `${wsOrigin}/api/ws/dashboard`
}

export class DashboardWsClient {
  private ws: WebSocket | null = null
  private status: Status = 'idle'
  private retry = 0
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private readonly url = resolveWsUrl()
  private readonly onMessage: Options['onMessage']
  private readonly onStatus?: Options['onStatus']

  constructor(opts: Options) {
    this.onMessage = opts.onMessage
    this.onStatus = opts.onStatus
  }

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return

    this.setStatus('connecting')
    this.ws = new WebSocket(this.url)

    this.ws.onopen = () => {
      this.retry = 0
      this.setStatus('open')
      this.startHeartbeat()
    }

    this.ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(String(e.data)) as WsDeviceMessage
        this.onMessage(msg)
      } catch {
        return
      }
    }

    this.ws.onclose = () => {
      this.stopHeartbeat()
      this.setStatus('closed')
      this.scheduleReconnect()
    }

    this.ws.onerror = () => {
      this.stopHeartbeat()
      this.setStatus('error')
      this.scheduleReconnect()
    }
  }

  close() {
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
    this.setStatus('closed')
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return
    const delay = Math.min(2000 * Math.pow(2, this.retry), 20000)
    this.retry += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private startHeartbeat() {
    if (this.heartbeatTimer) return
    this.heartbeatTimer = window.setInterval(() => {
      try {
        if (this.ws?.readyState === WebSocket.OPEN) this.ws.send('ping')
      } catch {
        return
      }
    }, 25000)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private setStatus(s: Status) {
    this.status = s
    this.onStatus?.(s)
  }
}

