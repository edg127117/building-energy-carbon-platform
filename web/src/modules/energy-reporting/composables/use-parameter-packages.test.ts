import { describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import type { ParameterPackageCatalog, ParameterPackageRequest } from '../models/parameter-packages'

const api = vi.hoisted(() => ({ catalog: vi.fn(), preview: vi.fn(), importPackage: vi.fn() }))
vi.mock('../api/parameter-packages', () => ({ parameterPackagesApi: api }))

import { useParameterPackages } from './use-parameter-packages'

function deferred<T>() {
  let resolve: (value: T) => void
  const promise = new Promise<T>(nextResolve => { resolve = nextResolve })
  return { promise, resolve: resolve! }
}

function catalog(code: string): ParameterPackageCatalog {
  return { packages: [], existingVersions: [], catalogRequirements: [], denominatorContext: [], marker: code } as ParameterPackageCatalog
}

const request = {
  packageCode: 'B01', itemCodes: ['STANDARD_COAL_LHV'], source: {}, effectiveFrom: null, effectiveTo: null,
  standardConditionCode: null, dependencies: {}, electricity: {}, heat: {}, idempotencyKey: 'parameter-key',
} as ParameterPackageRequest

describe('专业参数包上下文', () => {
  it('忽略建筑切换前迟到返回的目录，避免将旧目录用于新建筑导入', async () => {
    const first = deferred<ParameterPackageCatalog>()
    const second = deferred<ParameterPackageCatalog>()
    api.catalog.mockReset()
    api.catalog.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const workspace = useParameterPackages()

    const pendingFirst = workspace.load('B-1')
    const pendingSecond = workspace.load('B-2')
    second.resolve(catalog('B-2'))
    await pendingSecond
    first.resolve(catalog('B-1'))
    await pendingFirst

    expect(workspace.catalog.value).toMatchObject({ marker: 'B-2' })
    expect(workspace.loading.value).toBe(false)
  })

  it('将当前导入的权限失败留在页面状态，不把请求异常继续抛出', async () => {
    api.catalog.mockResolvedValue(catalog('B-1'))
    api.importPackage.mockRejectedValue(new TransportError('forbidden', 403))
    const workspace = useParameterPackages()
    await workspace.load('B-1')

    const result = await workspace.importPackage(request)

    expect(result).toBeNull()
    expect(workspace.error.value).not.toBe('')
    expect(workspace.saving.value).toBe(false)
  })
})
