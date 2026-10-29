import {
  approveChangeRequest,
  createChangeRequest,
  executeChangeRequest,
  submitChangeRequest,
} from '../api/access-control'
import type { SensitiveChange } from '../models/access-control'
import { t } from '@/locales'

export const D01_ADMIN_DUTIES = [
  'ENERGY_RULE_MAINTAIN',
  'ENERGY_RULE_REVIEW',
  'ENERGY_CALCULATION_RUN',
] as const

type ChangeAction = (requestId: string) => Promise<SensitiveChange>
type GrantApi = {
  create: typeof createChangeRequest
  submit: ChangeAction
  approve: (requestId: string, comment: string) => Promise<SensitiveChange>
  execute: ChangeAction
}

const api: GrantApi = {
  create: createChangeRequest,
  submit: submitChangeRequest,
  approve: approveChangeRequest,
  execute: executeChangeRequest,
}

export class GrantD01TerminalError extends Error {}

/** 一次操作的重试沿用幂等键；新操作必须换键，避免旧执行记录掩盖后续撤销。 */
export async function grantD01AdminDuties(userId: number, operationKey: string,
  onProgress: (completed: number, duty: string) => void,
  actions: GrantApi = api): Promise<void> {
  for (const [index, duty] of D01_ADMIN_DUTIES.entries()) {
    let change = await actions.create('GRANT_BACKEND_DUTY', { userId, dutyKey: duty },
      `d01:${operationKey}:${duty}`)
    if (change.status === 'DRAFT') change = await actions.submit(change.requestId)
    if (change.status === 'PENDING_REVIEW') {
      change = await actions.approve(change.requestId, t('accessControl.user.d01GrantReviewComment'))
    }
    if (change.status === 'APPROVED') change = await actions.execute(change.requestId)
    if (change.status === 'REJECTED' || change.status === 'WITHDRAWN'
      || change.status === 'EXECUTION_FAILED') {
      throw new GrantD01TerminalError(`${duty}: ${change.status}`)
    }
    if (change.status !== 'EXECUTED') throw new Error(`${duty}: ${change.status}`)
    onProgress(index + 1, duty)
  }
}
