import { describe, expect, it, vi } from 'vitest'
import type { SensitiveChange } from '../models/access-control'
import { D01_ADMIN_DUTIES, grantD01AdminDuties } from './grant-d01-admin-duties'

function state(status: SensitiveChange['status'], requestId = 'request-1'): SensitiveChange {
  return { status, requestId } as SensitiveChange
}

describe('D01 test admin duty grant', () => {
  it('requires draft, submit, review and execution for each persistent duty', async () => {
    const actions = {
      create: vi.fn(async () => state('DRAFT')),
      submit: vi.fn(async () => state('PENDING_REVIEW')),
      approve: vi.fn(async () => state('APPROVED')),
      execute: vi.fn(async () => state('EXECUTED')),
    }
    const progress = vi.fn()
    await grantD01AdminDuties(1, 'attempt-1', progress, actions)

    expect(actions.create).toHaveBeenCalledTimes(3)
    expect(actions.submit).toHaveBeenCalledTimes(3)
    expect(actions.approve).toHaveBeenCalledTimes(3)
    expect(actions.execute).toHaveBeenCalledTimes(3)
    D01_ADMIN_DUTIES.forEach((duty, index) => {
      expect(actions.create).toHaveBeenNthCalledWith(index + 1, 'GRANT_BACKEND_DUTY',
        { userId: 1, dutyKey: duty }, `d01:attempt-1:${duty}`)
      expect(progress).toHaveBeenNthCalledWith(index + 1, index + 1, duty)
    })
  })

  it('resumes an approved request without another approval', async () => {
    const actions = {
      create: vi.fn(async () => state('APPROVED')),
      submit: vi.fn(async () => state('PENDING_REVIEW')),
      approve: vi.fn(async () => state('APPROVED')),
      execute: vi.fn(async () => state('EXECUTED')),
    }
    await grantD01AdminDuties(1, 'attempt-1', vi.fn(), actions)
    expect(actions.submit).not.toHaveBeenCalled()
    expect(actions.approve).not.toHaveBeenCalled()
    expect(actions.execute).toHaveBeenCalledTimes(3)
  })

  it('stops after a failed review without claiming later duties', async () => {
    const actions = {
      create: vi.fn(async () => state('DRAFT')),
      submit: vi.fn(async () => state('PENDING_REVIEW')),
      approve: vi.fn(async () => state('REJECTED')),
      execute: vi.fn(async () => state('EXECUTED')),
    }
    const progress = vi.fn()
    await expect(grantD01AdminDuties(1, 'attempt-1', progress, actions)).rejects.toThrow('REJECTED')
    expect(actions.create).toHaveBeenCalledTimes(1)
    expect(actions.execute).not.toHaveBeenCalled()
    expect(progress).not.toHaveBeenCalled()
  })

  it('uses a new idempotency scope for a later grant after duties are revoked', async () => {
    const actions = {
      create: vi.fn(async () => state('EXECUTED')),
      submit: vi.fn(async () => state('PENDING_REVIEW')),
      approve: vi.fn(async () => state('APPROVED')),
      execute: vi.fn(async () => state('EXECUTED')),
    }
    await grantD01AdminDuties(1, 'first-attempt', vi.fn(), actions)
    await grantD01AdminDuties(1, 'later-attempt', vi.fn(), actions)
    expect(actions.create).toHaveBeenNthCalledWith(1, 'GRANT_BACKEND_DUTY',
      { userId: 1, dutyKey: 'ENERGY_RULE_MAINTAIN' }, 'd01:first-attempt:ENERGY_RULE_MAINTAIN')
    expect(actions.create).toHaveBeenNthCalledWith(4, 'GRANT_BACKEND_DUTY',
      { userId: 1, dutyKey: 'ENERGY_RULE_MAINTAIN' }, 'd01:later-attempt:ENERGY_RULE_MAINTAIN')
  })
})
