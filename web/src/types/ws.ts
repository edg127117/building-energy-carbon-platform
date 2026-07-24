export type WsDeviceMessage = {
  messageId?: string
  deviceId: string
  type: 'property' | 'event' | 'reply' | 'offline'
  data?: Record<string, unknown>
  timestamp?: number
}

