export interface ParameterPackageSource {
  sourceCode: string
  sourceName: string
  publisher: string
  documentReference: string
  sourceDocumentVersion: string
  publicationYear: number | null
  publishedOn: string | null
  applicabilityNote: string
  evidenceReference: string
  energySourceType: string
}

export interface ParameterPackageDependencies {
  standardCoalLhvVersionId: string | null
  naturalGasFormulaVersionId: string | null
  rawCoalFormulaVersionId: string | null
  rawCoalEnergyItemCode: string | null
}

export interface ParameterPackageElectricity {
  applicabilityLevel: string
  regionCode: string | null
  dataYear: number | null
  accountingYear: number | null
}

export interface ParameterPackageHeat {
  co2eEvidenceReference: string
}

export interface ParameterPackageRequest {
  packageCode: 'B01' | 'B02' | ''
  itemCodes: string[]
  source: ParameterPackageSource
  effectiveFrom: string | null
  effectiveTo: string | null
  standardConditionCode: string | null
  dependencies: ParameterPackageDependencies
  electricity: ParameterPackageElectricity
  heat: ParameterPackageHeat
  idempotencyKey: string
}

export interface ParameterPackageDefinitionItem {
  itemCode: string
  itemName: string
  targetType: string
  sourceValue: string | null
  sourceUnit: string
  normalizedValue: string | null
  normalizedUnit: string
  requiredInputs: string[]
}

export interface ParameterPackageDefinition {
  packageCode: 'B01' | 'B02'
  packageName: string
  items: ParameterPackageDefinitionItem[]
}

export interface ParameterPackageVersionContext {
  domain: string
  code: string
  versionId: string
  status: string
  usageNature: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
}

export interface ParameterPackageCatalogRequirement {
  catalogType: string
  requiredCode: string
  listEndpoint: string
  createEndpoint: string
  versions: ParameterPackageVersionContext[]
}

export interface ParameterPackageDenominatorContext {
  denominatorType: string
  versionId: string
  value: string
  unitCode: string
  status: string
  effectiveFrom: string
  effectiveTo: string | null
}

export interface ParameterPackageCatalog {
  packages: ParameterPackageDefinition[]
  existingVersions: ParameterPackageVersionContext[]
  catalogRequirements: ParameterPackageCatalogRequirement[]
  denominatorContext: ParameterPackageDenominatorContext[]
}

export interface ParameterPackageIssue {
  itemCode: string | null
  severity: 'BLOCKER' | 'WARNING' | 'NOTICE' | string
  code: string
  message: string
}

export interface ParameterPackageDependency {
  dependencyType: string
  versionId: string | null
  status: string
  message: string
}

export interface ParameterPackagePreviewItem {
  itemCode: string
  status: string
  normalizedValue: string | null
  normalizedUnit: string
  dependencies: ParameterPackageDependency[]
  issues: ParameterPackageIssue[]
}

export interface ParameterPackagePreview {
  packageCode: string
  readyForImport: boolean
  items: ParameterPackagePreviewItem[]
  issues: ParameterPackageIssue[]
}

export interface ParameterPackageCreatedVersion {
  itemCode: string
  objectType: string
  versionId: string
  status: string
  dependencyVersionIds: string[]
}

export interface ParameterPackageImport {
  packageCode: string
  completionStatus: string
  awaitingIndependentReview: boolean
  created: ParameterPackageCreatedVersion[]
  notices: ParameterPackageIssue[]
}
