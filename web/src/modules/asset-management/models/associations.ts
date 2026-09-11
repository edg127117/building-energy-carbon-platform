/** 只消费后端关系快照；空归属与当前投影明确保留，前端不推导生效关系。 */
export type RelationModel = {
  buildingId: string; governanceMode: string; activeVersionId: string | null
  draftVersionId: string | null; modelRevision: number
}
export type RelationVersion = {
  versionId: string; versionNo: number; status: string; revision: number
  baseVersionId: string | null; changeReason: string; submittedBy: number | null
}
export type RelationReview = {
  requestId: string; versionId: string; status: string; submittedBy: number
  reviewReason: string | null
}
export type EquipmentAssociation = {
  equipmentId: string; equipmentCode: string | null; equipmentName: string
  spaceId: string | null; spaceName: string | null
  systemGroupId: string | null; systemGroupName: string | null
}
export type AssociationPage = {
  buildingId: string; versionId: string | null; versionRevision: number | null; page: number; size: number; total: number
  items: EquipmentAssociation[]
  spaces: Array<{ spaceId: string; spaceName: string; parentSpaceId: string | null }>
  systems: Array<{ systemGroupId: string; systemName: string }>
}
export type RelationValidation = {
  errorCount: number; pendingExpertCount: number; warningCount: number
  issues: Array<{ issueId: string; level: string; code: string; message: string }>
}
export type RelationDiff = {
  addedCount: number; removedCount: number; truncated: boolean
  addedSamples: string[]; removedSamples: string[]
}
export type RelationAction = 'initialize' | 'create' | 'submit' | 'withdraw' | 'approve' | 'reject' | 'activate'
