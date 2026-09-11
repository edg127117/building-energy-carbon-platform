import { computed, ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import {
  approveChangeRequest,
  createChangeRequest,
  executeChangeRequest,
  getChangeRequest,
  newIdempotencyKey,
  rejectChangeRequest,
  submitChangeRequest,
  withdrawChangeRequest,
} from '../api/access-control'
import type { SensitiveChange, SensitiveChangeOperation } from '../models/access-control'

function addPending(current: Set<string>, key: string): Set<string> {
  const next = new Set(current)
  next.add(key)
  return next
}

function removePending(current: Set<string>, key: string): Set<string> {
  const next = new Set(current)
  next.delete(key)
  return next
}

/** 敏感命令始终停在申请、审核和执行的显式步骤，页面不会代替审核人自动执行。 */
export function useSensitiveChange() {
  const current = ref<SensitiveChange | null>(null)
  const pending = ref(new Set<string>())
  const error = ref<string | null>(null)

  async function run(key: string, action: () => Promise<SensitiveChange>) {
    if (pending.value.has(key)) return undefined
    pending.value = addPending(pending.value, key)
    error.value = null
    try {
      const value = await action()
      current.value = value
      return value
    } catch (reason) {
      error.value = requestErrorMessage(reason)
      throw reason
    } finally {
      pending.value = removePending(pending.value, key)
    }
  }

  async function start(
    operationCode: SensitiveChangeOperation,
    command: Record<string, unknown>,
    actionKey: string = operationCode,
  ) {
    return run(`start:${actionKey}`, async () => {
      const draft = await createChangeRequest(operationCode, command, newIdempotencyKey())
      current.value = draft
      return submitChangeRequest(draft.requestId)
    })
  }

  function load(requestId: string) {
    return run(`load:${requestId}`, () => getChangeRequest(requestId))
  }

  function submit(requestId: string) {
    return run(`submit:${requestId}`, () => submitChangeRequest(requestId))
  }

  function withdraw(requestId: string) {
    return run(`withdraw:${requestId}`, () => withdrawChangeRequest(requestId))
  }

  function approve(requestId: string, comment: string) {
    return run(`approve:${requestId}`, () => approveChangeRequest(requestId, comment))
  }

  function reject(requestId: string, comment: string) {
    return run(`reject:${requestId}`, () => rejectChangeRequest(requestId, comment))
  }

  function execute(requestId: string) {
    return run(`execute:${requestId}`, () => executeChangeRequest(requestId))
  }

  return {
    current,
    pending,
    error: computed(() => error.value),
    isPending: (key: string) => pending.value.has(key),
    start,
    load,
    submit,
    withdraw,
    approve,
    reject,
    execute,
  }
}
