import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { ref } from 'vue'
import { errorCode } from '../api/weather'
import w from '../locales/zh-CN'

/** 仅在明确成功后消费幂等键；网络结果不明时，相同负载的重试沿用原键。 */
export function useCommand() {
  const pending = ref(false)
  const message = ref('')
  const failed = ref(false)
  const keys = new Map<string, string>()
  async function run<T>(identity: string, payload: unknown, send: (key: string) => Promise<T>, success = w.submitted): Promise<T | undefined> {
    if (pending.value) return
    const signature = JSON.stringify([identity, payload])
    const key = keys.get(signature) ?? createIdempotencyKey()
    keys.set(signature, key)
    pending.value = true; message.value = ''; failed.value = false
    try { const result = await send(key); keys.delete(signature); message.value = success; return result }
    catch (error) { failed.value = true; message.value = w.errors[errorCode(error) ?? ''] ?? w.submitFailed }
    finally { pending.value = false }
  }
  return { pending, message, failed, run }
}
