let fallbackSequence = 0

/** 仅生成请求关联键，不作鉴权凭据；HTTP 缺少 randomUUID 时仍保留随机字节来源。 */
export function createIdempotencyKey(): string {
  const source = globalThis.crypto
  if (typeof source?.randomUUID === 'function') return source.randomUUID()
  if (typeof source?.getRandomValues === 'function') {
    const bytes = source.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  // 无 Web Crypto 的旧环境也不能只用时间戳，否则同毫秒的不同命令可能共用幂等键。
  return `web-${Date.now().toString(36)}-${(++fallbackSequence).toString(36)}-${Math.random().toString(36).slice(2)}`
}
