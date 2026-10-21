import type { BindingSemantics, ClassificationBinding, ClassificationNode, ClassificationObjectType, ReviewStatus } from './classification'

export interface BindingBatchSelection {
  schemeCode: string
  nodeCode: string
  objectType: ClassificationObjectType
  objectIds: string[]
  evidence: string
  semantics?: BindingSemantics
  status?: ReviewStatus
}

export function nextClassificationNodeCode(nodes: ClassificationNode[]): string {
  const used = new Set(nodes.map(node => node.code.trim().toUpperCase()))
  let sequence = 1
  while (used.has(`NODE_${String(sequence).padStart(3, '0')}`)) sequence++
  return `NODE_${String(sequence).padStart(3, '0')}`
}

export function createBindingBatch(selection: BindingBatchSelection): ClassificationBinding[] {
  const objectIds = [...new Set(selection.objectIds.map(id => id.trim()).filter(Boolean))]
  if (objectIds.length > 100) throw new Error('BINDING_BATCH_LIMIT')
  const evidence = selection.evidence.trim()
  if (!evidence || evidence.length > 500) throw new Error('BINDING_EVIDENCE_INVALID')
  return objectIds.map(objectId => ({
    schemeCode: selection.schemeCode,
    nodeCode: selection.nodeCode,
    objectType: selection.objectType,
    objectId,
    meterPointId: null,
    semantics: selection.semantics ?? 'DESCRIPTION',
    status: selection.status ?? 'PENDING_EXPERT',
    evidence,
  }))
}

export function mergeBindingBatch(existing: ClassificationBinding[], incoming: ClassificationBinding[]): ClassificationBinding[] {
  const key = (binding: ClassificationBinding) => JSON.stringify([binding.schemeCode, binding.nodeCode, binding.objectType, binding.objectId, binding.meterPointId, binding.semantics])
  const identities = new Set(existing.map(key))
  return incoming.filter(binding => {
    const identity = key(binding)
    if (identities.has(identity)) return false
    identities.add(identity)
    return true
  })
}

export function updateBindingBatch(existing: ClassificationBinding[], incoming: ClassificationBinding[]) {
  const key = (binding: ClassificationBinding) => JSON.stringify([binding.schemeCode, binding.nodeCode, binding.objectType, binding.objectId, binding.semantics])
  const selected = new Map(incoming.map(binding => [key(binding), binding]))
  let matched = 0
  const bindings = existing.map(binding => {
    const update = selected.get(key(binding))
    if (!update) return binding
    matched++
    // 只更新用户明确选择的确认状态与依据，保留原计量通道及身份。
    return { ...binding, status: update.status, evidence: update.evidence }
  })
  return { bindings, matched }
}

export function parentOptionsForNode(node: Pick<ClassificationNode, 'schemeCode' | 'code'>, nodes: ClassificationNode[]): ClassificationNode[] {
  return nodes.filter(candidate => {
    if (candidate.schemeCode !== node.schemeCode || candidate.code === node.code) return false
    const seen = new Set<string>()
    let current: ClassificationNode | undefined = candidate
    while (current) {
      if (current.code === node.code || seen.has(current.code)) return false
      seen.add(current.code)
      const parentCode = current.parentCode
      current = parentCode ? nodes.find(item => item.schemeCode === node.schemeCode && item.code === parentCode) : undefined
    }
    return true
  })
}

export function localDateTimeToUtc(value: string): string | null {
  if (!value) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const [, year, month, day, hour, minute] = match
  if (date.getFullYear() !== Number(year) || date.getMonth() + 1 !== Number(month)
    || date.getDate() !== Number(day) || date.getHours() !== Number(hour) || date.getMinutes() !== Number(minute)) return null
  return date.toISOString()
}

export function utcToLocalDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
